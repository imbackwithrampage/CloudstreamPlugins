package com.likdev256

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import com.lagradost.nicehttp.NiceResponse
import okhttp3.FormBody

@SuppressWarnings("deprecation")
class TamilUltraProvider : MainAPI() { // all providers must be an instance of MainAPI
    override var mainUrl = "https://tamilultratv.co.in"
    override var name = "TamilUltra"
    override val hasMainPage = true
    override var lang = "ta"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Live
    )

    override val mainPage = mainPageOf(
        "$mainUrl/channels/sports/" to "Sports",
        "$mainUrl/channels/english/" to "English",
        "$mainUrl/channels/tamil/" to "Tamil",
        "$mainUrl/channels/telugu/" to "Telugu",
        "$mainUrl/channels/kannada/" to "Kannada",
        "$mainUrl/channels/malayalam-tv-channels/" to "Malayalam"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = if (page == 1) {
            app.get(request.data).document
        } else {
            app.get(request.data + "/page/$page/").document
        }

        //Log.d("Document", request.data)
        val home = document.select("div.items > article.item").mapNotNull {
                it.toSearchResult()
            }

        return HomePageResponse(arrayListOf(HomePageList(request.name, home)), hasNext = true)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        //Log.d("Got","got here")
        val title = this.selectFirst("div.data > h3 > a")?.text()?.toString()?.trim()
            ?: return null
        //Log.d("title", title)
        val href = fixUrl(this.selectFirst("div.data > h3 > a")?.attr("href").toString())
        //Log.d("href", href)
        val posterUrl = fixUrlNull(this.selectFirst("div.poster > img")?.attr("src"))
        //Log.d("posterUrl", posterUrl.toString())
        return newMovieSearchResponse(title, href, TvType.Live) {
                this.posterUrl = posterUrl
            }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        //Log.d("document", document.toString())

        return document.select("div.result-item").mapNotNull {
            val title =
                it.selectFirst("article > div.details > div.title > a")?.text().toString().trim()
            //Log.d("title", titleS)
            val href = fixUrl(
                it.selectFirst("article > div.details > div.title > a")?.attr("href").toString()
            )
            //Log.d("href", href)
            val posterUrl = fixUrlNull(
                it.selectFirst("article > div.image > div.thumbnail > a > img")?.attr("src")
            )
            //Log.d("posterUrl", posterUrl.toString())

            newMovieSearchResponse(title, href, TvType.Live) {
                    this.posterUrl = posterUrl
                }
        }
    }

    private suspend fun getEmbed(postid: String?, nume: String, referUrl: String?): NiceResponse {
        val body = FormBody.Builder()
            .addEncoded("action", "doo_player_ajax")
            .addEncoded("post", postid.toString())
            .addEncoded("nume", nume)
            .addEncoded("type", "movie")
            .build()

        return app.post(
            "$mainUrl/wp-admin/admin-ajax.php",
            requestBody = body,
            referer = referUrl
        )
    }

    data class EmbedUrl (
        @JsonProperty("embed_url") var embedUrl : String,
        @JsonProperty("type") var type : String?
    )

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        //Log.d("Doc", doc.toString())
        val title = doc.select("div.sheader > div.data > h1").text()
        //Log.d("title", title)
        val poster = fixUrlNull(doc.selectFirst("div.poster > img")?.attr("src"))
        val id = doc.select("#player-option-1").attr("data-post")

        return newMovieLoadResponse(title, id, TvType.Live, "$url,$id") {
                this.posterUrl = poster
            }
    }

    @Suppress("DEPRECATION")
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val referer = data.substringBefore(",")
        val id = data.substringAfter(",")
        
        // Get embed response
        val embedResponse = getEmbed(id, "1", referer)
        val embedData = embedResponse.parsed<EmbedUrl>()
        val embedUrl = fixUrlNull(embedData.embedUrl) ?: return false
        
        // Log for debugging
        Log.d("TamilUltra", "Embed URL: $embedUrl")
        
        // Handle iframe URL
        val iframeDoc = app.get(embedUrl, referer = referer).document
        
        // Extract direct stream URL from iframe
        // Look for source tags or script that contains stream URLs
        val directUrls = iframeDoc.select("source").mapNotNull { it.attr("src") }
        directUrls.forEach { sourceUrl ->
            Log.d("TamilUltra", "Source URL: $sourceUrl")
            if (sourceUrl.contains(".m3u8")) {
                M3u8Helper.generateM3u8(
                    this.name,
                    sourceUrl,
                    embedUrl
                ).forEach(callback)
            } else {
                callback.invoke(
                    ExtractorLink(
                        this.name,
                        this.name,
                        sourceUrl,
                        embedUrl,
                        Qualities.Unknown.value,
                        false
                    )
                )
            }
        }
        
        // If no source tags, check for m3u8 URLs in scripts
        if (directUrls.isEmpty()) {
            val scriptData = iframeDoc.select("script").map { it.data() }.joinToString("\n")
            val m3u8Regex = Regex("['\"](https?://[^'\"]+\\.m3u8[^'\"]*)['\"]")
            val m3u8Urls = m3u8Regex.findAll(scriptData).map { it.groupValues[1] }.toList()
            
            m3u8Urls.forEach { m3u8Url ->
                Log.d("TamilUltra", "M3U8 URL from script: $m3u8Url")
                M3u8Helper.generateM3u8(
                    this.name,
                    m3u8Url,
                    embedUrl
                ).forEach(callback)
            }
            
            // Check for stream URL in params
            if (embedUrl.contains(".php?")) {
                val streamUrl = embedUrl.substringAfter(".php?")
                Log.d("TamilUltra", "Stream URL from params: $streamUrl")
                if (streamUrl.isNotBlank() && (streamUrl.startsWith("http") || streamUrl.startsWith("//"))) {
                    val finalUrl = if (streamUrl.startsWith("//")) "https:$streamUrl" else streamUrl
                    callback.invoke(
                        ExtractorLink(
                            this.name,
                            this.name,
                            finalUrl,
                            embedUrl,
                            Qualities.Unknown.value,
                            finalUrl.contains(".m3u8")
                        )
                    )
                }
            }
        }
        
        // For debugging, try to directly load the embed URL itself if it's a player
        if (embedUrl.contains("player") || embedUrl.contains("stream") || embedUrl.contains(".php")) {
            callback.invoke(
                ExtractorLink(
                    this.name,
                    "$name Direct",
                    embedUrl,
                    referer,
                    Qualities.Unknown.value,
                    embedUrl.contains(".m3u8")
                )
            )
        }

        return true
    }
}

