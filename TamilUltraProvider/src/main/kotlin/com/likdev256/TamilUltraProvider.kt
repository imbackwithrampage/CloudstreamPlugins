package com.likdev256

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import com.lagradost.nicehttp.NiceResponse
import okhttp3.FormBody

class TamilUltraProvider : MainAPI() { // all providers must be an instance of MainAPI
    override var mainUrl = "https://tamilultra.fr"
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

        val home = document.select("div.items > article.item").mapNotNull {
                it.toSearchResult()
            }

        return HomePageResponse(arrayListOf(HomePageList(request.name, home)), hasNext = true)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("div.data > h3 > a")?.text()?.toString()?.trim()
            ?: return null
        val href = fixUrl(this.selectFirst("div.data > h3 > a")?.attr("href").toString())
        val posterUrl = fixUrlNull(this.selectFirst("div.poster > img")?.attr("src"))
        return newMovieSearchResponse(title, href, TvType.Live) {
                this.posterUrl = posterUrl
            }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document

        return document.select("div.result-item").mapNotNull {
            val title =
                it.selectFirst("article > div.details > div.title > a")?.text().toString().trim()
            val href = fixUrl(
                it.selectFirst("article > div.details > div.title > a")?.attr("href").toString()
            )
            val posterUrl = fixUrlNull(
                it.selectFirst("article > div.image > div.thumbnail > a > img")?.attr("src")
            )

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
        val title = doc.select("div.sheader > div.data > h1").text()
        val poster = fixUrlNull(doc.selectFirst("div.poster > img")?.attr("src"))
        val id = doc.select("#player-option-1").attr("data-post")

        return newMovieLoadResponse(title, id, TvType.Live, "$url,$id") {
                this.posterUrl = poster
            }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val referer = data.substringBefore(",")
            val id = data.substringAfter(",")
            
            // Get embed response
            val embedResponse = getEmbed(id, "1", referer)
            val embedData = try {
                embedResponse.parsed<EmbedUrl>()
            } catch (e: Exception) {
                Log.e("TamilUltra", "Error parsing embed URL: ${e.message}")
                return false
            }
            
            val embedUrl = fixUrlNull(embedData.embedUrl) ?: return false
            
            // Try loadExtractor first
            loadExtractor(embedUrl, referer, subtitleCallback, callback)
            
            // Try direct M3u8 handling
            try {
                val iframeDoc = app.get(embedUrl, referer = referer).document
                val iframeHtml = iframeDoc.html()
                
                // Look for source tags
                val sourceUrls = iframeDoc.select("source").mapNotNull { 
                    it.attr("src").takeIf { it.isNotBlank() }
                }
                
                // Look for m3u8 URLs in the HTML
                val m3u8Patterns = listOf(
                    Regex("['\"](https?://[^'\"]+?\\.m3u8(?:[^'\"]*?))['\"]"),
                    Regex("['\"]([^'\"]+?\\.m3u8(?:[^'\"]*?))['\"]"),
                    Regex("file:\\s*['\"](https?://[^'\"]+?\\.m3u8(?:[^'\"]*?))['\"]"),
                    Regex("source:\\s*['\"](https?://[^'\"]+?\\.m3u8(?:[^'\"]*?))['\"]"),
                    Regex("src:\\s*['\"](https?://[^'\"]+?\\.m3u8(?:[^'\"]*?))['\"]"),
                    Regex("\\{[^\\}]*?['\"](?:file|source|src|url|link|stream)['\"]\\s*:\\s*['\"]([^'\"]+?\\.m3u8[^'\"]*?)['\"]")
                )
                
                val m3u8Urls = mutableListOf<String>()
                m3u8Patterns.forEach { pattern ->
                    pattern.findAll(iframeHtml).mapNotNull { 
                        it.groupValues.lastOrNull { group -> group.isNotBlank() && group.contains(".m3u8") }
                    }.toList().also { m3u8Urls.addAll(it) }
                }
                
                // Process URLs from iframes
                iframeDoc.select("iframe").forEach { iframe ->
                    val src = iframe.attr("src")
                    if (src.isNotBlank()) {
                        try {
                            val fullUrl = if (src.startsWith("//")) "https:$src" 
                                else if (src.startsWith("/")) "$mainUrl$src" 
                                else src
                            val childDoc = app.get(fullUrl, referer = embedUrl).document
                            val childHtml = childDoc.html()
                            
                            m3u8Patterns.forEach { pattern ->
                                pattern.findAll(childHtml).mapNotNull {
                                    it.groupValues.lastOrNull { group -> group.isNotBlank() && group.contains(".m3u8") }
                                }.toList().also { m3u8Urls.addAll(it) }
                            }
                        } catch (e: Exception) {
                            Log.e("TamilUltra", "Error processing iframe: ${e.message}")
                        }
                    }
                }
                
                // Process any data attributes
                iframeDoc.select("[data-stream], [data-src], [data-source]").forEach { element ->
                    listOf("data-stream", "data-src", "data-source").forEach { attr ->
                        val value = element.attr(attr)
                        if (value.isNotBlank() && value.contains(".m3u8")) {
                            m3u8Urls.add(value)
                        }
                    }
                }
                
                // Combine and normalize all URLs
                val allStreamUrls = (sourceUrls + m3u8Urls).filter { it.isNotBlank() }
                    .map { 
                        if (it.startsWith("//")) "https:$it" 
                        else if (it.startsWith("/")) "$mainUrl$it" 
                        else it
                    }
                    .distinct()
                
                // Process M3U8 links
                allStreamUrls.forEach { url ->
                    if (url.contains(".m3u8")) {
                        try {
                            M3u8Helper.generateM3u8(
                                name,
                                url,
                                embedUrl
                            ).forEach(callback)
                        } catch (e: Exception) {
                            Log.e("TamilUltra", "Error processing M3U8: ${e.message}")
                        }
                    }
                }
                
                // Check for stream URL in query params
                if (embedUrl.contains("?")) {
                    val queryString = embedUrl.substringAfter("?")
                    queryString.split("&").forEach { param ->
                        val parts = param.split("=", limit = 2)
                        if (parts.size == 2) {
                            val paramName = parts[0]
                            val paramValue = parts[1]
                            
                            if (listOf("source", "src", "file", "stream", "url", "link").contains(paramName) &&
                                paramValue.isNotBlank() && (paramValue.contains(".m3u8") || paramValue.contains("//"))) {
                                
                                val streamUrl = if (paramValue.startsWith("//")) "https:$paramValue" else paramValue
                                if (streamUrl.contains(".m3u8")) {
                                    try {
                                        M3u8Helper.generateM3u8(
                                            name,
                                            streamUrl,
                                            embedUrl
                                        ).forEach(callback)
                                    } catch (e: Exception) {
                                        Log.e("TamilUltra", "Error processing query param stream: ${e.message}")
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("TamilUltra", "Error in main stream processing: ${e.message}")
                
                // Fallback to direct M3u8 helper if embedUrl itself is an m3u8 file
                if (embedUrl.contains(".m3u8")) {
                    try {
                        M3u8Helper.generateM3u8(
                            name,
                            embedUrl,
                            referer
                        ).forEach(callback)
                    } catch (e2: Exception) {
                        Log.e("TamilUltra", "Error in fallback stream processing: ${e2.message}")
                    }
                }
            }
            
            return true
        } catch (e: Exception) {
            Log.e("TamilUltra", "General error: ${e.message}")
            return false
        }
    }
}

