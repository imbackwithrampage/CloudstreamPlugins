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
        try {
            val referer = data.substringBefore(",")
            val id = data.substringAfter(",")
            
            // Get embed response
            val embedResponse = getEmbed(id, "1", referer)
            val embedData = try {
                embedResponse.parsed<EmbedUrl>()
            } catch (e: Exception) {
                logError(e)
                return false
            }
            
            val embedUrl = fixUrlNull(embedData.embedUrl) ?: return false
            
            // Try loadExtractor first
            loadExtractor(embedUrl, referer, subtitleCallback, callback)
            
            // Try direct M3u8 handling
            try {
                val iframeDoc = app.get(embedUrl, referer = referer).document
                
                // Look for source tags
                val sourceUrls = iframeDoc.select("source").mapNotNull { 
                    it.attr("src").takeIf { it.isNotBlank() }
                }
                
                // Look for m3u8 URLs in scripts
                val scriptData = iframeDoc.select("script").mapNotNull { 
                    try { it.data() } catch (e: Exception) { null } 
                }.joinToString("\n")
                // Enhanced regex to catch more variations of m3u8 URLs
                val m3u8Regex = Regex("['\"](https?://[^'\"]+?\\.m3u8(?:[^'\"]*?))['\"]")
                val m3u8Urls = m3u8Regex.findAll(scriptData).map { it.groupValues[1] }.toList()
                
                // Combine all URLs
                val allStreamUrls = (sourceUrls + m3u8Urls).filter { it.isNotBlank() }.distinct()
                
                // Process M3U8 links
                allStreamUrls.forEach { url ->
                    if (url.contains(".m3u8")) {
                        // Try to detect quality from URL
                        val quality = when {
                            url.contains("_hd") || url.contains("1080") -> 1080
                            url.contains("_720") || url.contains("720") -> 720
                            url.contains("_480") || url.contains("480") -> 480
                            url.contains("_360") || url.contains("360") -> 360
                            else -> 0
                        }
                        
                        M3u8Helper.generateM3u8(
                            name,
                            url,
                            embedUrl,
                            headers = mapOf("Referer" to embedUrl)
                        ).forEach { link ->
                            // Override quality if detected from URL and link's quality is unknown
                            if (quality > 0 && link.quality == 0) {
                                callback(
                                    ExtractorLink(
                                        link.source,
                                        link.name,
                                        link.url,
                                        link.referer,
                                        quality,
                                        link.isM3u8,
                                        link.headers,
                                        link.extractorData
                                    )
                                )
                            } else {
                                callback(link)
                            }
                        }
                    }
                }
                
                // Check for stream URL in params
                if (embedUrl.contains(".php?")) {
                    // Enhanced PHP parameter parsing
                    val queryParams = try {
                        val queryString = embedUrl.substringAfter(".php?", "")
                        if (queryString.isNotBlank()) {
                            queryString.split("&").mapNotNull { param ->
                                val parts = param.split("=", limit = 2)
                                if (parts.size == 2) parts[0] to parts[1] else null
                            }.toMap()
                        } else emptyMap()
                    } catch (e: Exception) {
                        emptyMap<String, String>()
                    }
                    
                    // Check common parameter names used for stream URLs
                    val possibleStreamParams = listOf("source", "src", "file", "stream", "url", "link", "video")
                    val streamUrl = possibleStreamParams.firstNotNullOfOrNull { param ->
                        queryParams[param]?.takeIf { it.isNotBlank() }
                    } ?: embedUrl.substringAfter(".php?")
                    
                    if (streamUrl.isNotBlank() && (streamUrl.startsWith("http") || streamUrl.startsWith("//"))) {
                        val finalUrl = if (streamUrl.startsWith("//")) "https:$streamUrl" else streamUrl
                        if (finalUrl.contains(".m3u8")) {
                            try {
                                // Try to detect quality from URL
                                val quality = when {
                                    finalUrl.contains("_hd") || finalUrl.contains("1080") -> 1080
                                    finalUrl.contains("_720") || finalUrl.contains("720") -> 720
                                    finalUrl.contains("_480") || finalUrl.contains("480") -> 480
                                    finalUrl.contains("_360") || finalUrl.contains("360") -> 360
                                    else -> 0
                                }
                                
                                M3u8Helper.generateM3u8(
                                    name,
                                    finalUrl,
                                    embedUrl,
                                    headers = mapOf("Referer" to embedUrl)
                                ).forEach { link ->
                                    // Override quality if detected from URL and link's quality is unknown
                                    if (quality > 0 && link.quality == 0) {
                                        callback(
                                            ExtractorLink(
                                                link.source,
                                                link.name,
                                                link.url,
                                                link.referer,
                                                quality,
                                                link.isM3u8,
                                                link.headers,
                                                link.extractorData
                                            )
                                        )
                                    } else {
                                        callback(link)
                                    }
                                }
                            } catch (e: Exception) {
                                logError(e)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Fallback to direct M3u8 helper
                if (embedUrl.contains(".m3u8")) {
                    try {
                        // Try to detect quality from URL
                        val quality = when {
                            embedUrl.contains("_hd") || embedUrl.contains("1080") -> 1080
                            embedUrl.contains("_720") || embedUrl.contains("720") -> 720
                            embedUrl.contains("_480") || embedUrl.contains("480") -> 480
                            embedUrl.contains("_360") || embedUrl.contains("360") -> 360
                            else -> 0
                        }

                        M3u8Helper.generateM3u8(
                            name,
                            embedUrl,
                            referer,
                            headers = mapOf("Referer" to referer)
                        ).forEach { link ->
                            // Override quality if detected from URL and link's quality is unknown
                            if (quality > 0 && link.quality == 0) {
                                callback(
                                    ExtractorLink(
                                        link.source,
                                        link.name,
                                        link.url,
                                        link.referer,
                                        quality,
                                        link.isM3u8,
                                        link.headers,
                                        link.extractorData
                                    )
                                )
                            } else {
                                callback(link)
                            }
                        }
                    } catch (e2: Exception) {
                        // Final fallback: try direct link extraction
                        logError(e2)
                        callback(
                            ExtractorLink(
                                name,
                                name,
                                embedUrl,
                                referer,
                                0,
                                true
                            )
                        )
                    }
                }
            }
            
            return true
        } catch (e: Exception) {
            logError(e)
            return false
        }
    }
    
    private fun logError(e: Exception) {
        try {
            Log.e("TamilUltra", "Error: ${e.message}", e)
        } catch (_: Exception) {
            // Ignore logging errors
        }
    }
}

