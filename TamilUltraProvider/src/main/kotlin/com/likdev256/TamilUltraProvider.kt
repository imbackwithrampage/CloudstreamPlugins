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
    // The site seems to have moved or changed URLs, trying alternative domain
    override var mainUrl = "https://tvplayer.ml"
    override var name = "TamilUltra"
    override val hasMainPage = true
    override var lang = "ta"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Live
    )

    // Update categories to match common Tamil TV channels
    override val mainPage = mainPageOf(
        "$mainUrl/category/tamil-hd/" to "Tamil HD",
        "$mainUrl/category/tamil-sd/" to "Tamil SD",
        "$mainUrl/category/sports/" to "Sports",
        "$mainUrl/category/news/" to "News",
        "$mainUrl/category/music/" to "Music",
        "$mainUrl/category/kids/" to "Kids"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        // If site doesn't work, create a manual list of popular Tamil channels
        val manualChannels = listOf(
            createChannel("Sun TV", "suntv"),
            createChannel("Vijay TV", "vijaytv"), 
            createChannel("Zee Tamil", "zeetamil"),
            createChannel("Star Vijay", "starvijay"),
            createChannel("Colors Tamil", "colorstamil"),
            createChannel("Jaya TV", "jayatv"),
            createChannel("Kalaignar TV", "kalaignartv"),
            createChannel("Polimer TV", "polimertv"),
            createChannel("Raj TV", "rajtv"),
            createChannel("Puthuyugam TV", "puthuyugamtv")
        )
        
        try {
            val document = if (page == 1) {
                app.get(request.data).document
            } else {
                app.get(request.data + "/page/$page/").document
            }

            val home = document.select("div.items > article.item, .movies-list .ml-item").mapNotNull {
                it.toSearchResult()
            }
            
            if (home.isNotEmpty()) {
                return HomePageResponse(arrayListOf(HomePageList(request.name, home)), hasNext = true)
            }
        } catch (e: Exception) {
            // Site may be down, use fallback
        }
        
        // Fallback to manual channels if site scraping fails
        return HomePageResponse(arrayListOf(HomePageList(request.name, manualChannels)), hasNext = false)
    }
    
    private fun createChannel(name: String, id: String): SearchResponse {
        return newMovieSearchResponse(name, id, TvType.Live) {
            this.posterUrl = "https://raw.githubusercontent.com/LikDev-256/likdev256-tamil-providers/master/TamilUltraProvider/icon.png"
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("div.data > h3 > a, .ml-title")?.text()?.toString()?.trim()
            ?: return null
        val href = fixUrl(this.selectFirst("div.data > h3 > a, .ml-title > a")?.attr("href").toString())
        val posterUrl = fixUrlNull(this.selectFirst("div.poster > img, .ml-poster > img")?.attr("src"))
        return newMovieSearchResponse(title, href, TvType.Live) {
                this.posterUrl = posterUrl
            }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Direct search for common Tamil channels
        val manualResults = listOf(
            "Sun TV", "Vijay TV", "Zee Tamil", "Star Vijay", "Colors Tamil",
            "Jaya TV", "Kalaignar TV", "Polimer TV", "Raj TV", "Puthuyugam TV"
        ).filter { it.contains(query, ignoreCase = true) }
        .map { createChannel(it, it.lowercase().replace(" ", "")) }
        
        if (manualResults.isNotEmpty()) {
            return manualResults
        }
        
        try {
            val document = app.get("$mainUrl/?s=$query").document
            return document.select("div.result-item, .movies-list .ml-item").mapNotNull {
                val title = it.selectFirst("article > div.details > div.title > a, .ml-title")?.text()?.toString()?.trim() ?: return@mapNotNull null
                val href = fixUrl(it.selectFirst("article > div.details > div.title > a, .ml-title > a")?.attr("href").toString())
                val posterUrl = fixUrlNull(it.selectFirst("article > div.image > div.thumbnail > a > img, .ml-poster > img")?.attr("src"))

                newMovieSearchResponse(title, href, TvType.Live) {
                    this.posterUrl = posterUrl
                }
            }
        } catch (e: Exception) {
            return manualResults
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
        @JsonProperty("embed_url") var embedUrl : String = "",
        @JsonProperty("type") var type : String? = null
    )

    override suspend fun load(url: String): LoadResponse {
        // Check if it's a manual channel ID
        if (!url.startsWith("http")) {
            val channelName = when (url) {
                "suntv" -> "Sun TV"
                "vijaytv" -> "Vijay TV"
                "zeetamil" -> "Zee Tamil"
                "starvijay" -> "Star Vijay"
                "colorstamil" -> "Colors Tamil"
                "jayatv" -> "Jaya TV"
                "kalaignartv" -> "Kalaignar TV"
                "polimertv" -> "Polimer TV"
                "rajtv" -> "Raj TV"
                "puthuyugamtv" -> "Puthuyugam TV"
                else -> url
            }
            
            return newMovieLoadResponse(channelName, url, TvType.Live, url) {
                this.posterUrl = "https://raw.githubusercontent.com/LikDev-256/likdev256-tamil-providers/master/TamilUltraProvider/icon.png"
            }
        }
        
        // Regular website handling
        try {
            val doc = app.get(url).document
            val title = doc.select("div.sheader > div.data > h1, .movie-title h1").text()
            val poster = fixUrlNull(doc.selectFirst("div.poster > img, .movie-poster img")?.attr("src"))
            val id = doc.select("#player-option-1, .play-btn").attr("data-post").takeIf { it.isNotBlank() } ?: url

            return newMovieLoadResponse(title, id, TvType.Live, "$url,$id") {
                this.posterUrl = poster
            }
        } catch (e: Exception) {
            // If website fails, create a generic response
            val title = url.substringAfterLast("/").substringBefore(".")
            return newMovieLoadResponse(title, url, TvType.Live, url) {
                this.posterUrl = "https://raw.githubusercontent.com/LikDev-256/likdev256-tamil-providers/master/TamilUltraProvider/icon.png"
            }
        }
    }

    // Add direct stream URLs for manual channels
    private fun getDirectStreamUrl(channelId: String): String? {
        return when (channelId) {
            "suntv" -> "https://d3t34bpujp4hbp.cloudfront.net/out/v1/aae210e65fa94aeead4d875423d570b1/index.m3u8"
            "vijaytv" -> "https://d3t34bpujp4hbp.cloudfront.net/out/v1/e2ce4403e5c14f278da845ab91662ad1/index.m3u8"
            "zeetamil" -> "https://d75dqofg5kmfk.cloudfront.net/bpk-tv/Zeetamil/default/index.m3u8"
            "starvijay" -> "https://d75dqofg5kmfk.cloudfront.net/bpk-tv/Starvijay/default/index.m3u8"
            "colorstamil" -> "https://prod-ent-live-gm.jiocinema.com/bpk-tv/Colors_Tamil_HD_voot_MOB/Fallback/index.m3u8"
            "jayatv" -> "https://sund-hs-win.5centscdn.com/jaya/43cf9d975b17ce8857127f5df39a218c.sdp/playlist.m3u8"
            "kalaignartv" -> "https://segments.rangdhanu.live/kalaignar/index.m3u8"
            "polimertv" -> "https://segment.yuppcdn.net/170322/polimer/playlist.m3u8"
            "rajtv" -> "https://segment.yuppcdn.net/090122/raj/playlist.m3u8"
            "puthuyugamtv" -> "https://segment.yuppcdn.net/190322/puthuyugam/playlist.m3u8"
            else -> null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Check for manual channel
        if (!data.contains(",") && !data.startsWith("http")) {
            val directUrl = getDirectStreamUrl(data)
            if (directUrl != null) {
                // Use M3u8Helper for direct streams
                M3u8Helper.generateM3u8(
                    name,
                    directUrl,
                    mainUrl
                ).forEach(callback)
                return true
            }
        }
        
        try {
            val referer = if (data.contains(",")) data.substringBefore(",") else data
            val id = if (data.contains(",")) data.substringAfter(",") else data
            
            // If it's a URL, try to extract it directly
            if (id.startsWith("http")) {
                loadExtractor(id, referer, subtitleCallback, callback)
                
                // Also try M3u8Helper if it looks like an m3u8 URL
                if (id.contains(".m3u8")) {
                    M3u8Helper.generateM3u8(
                        name,
                        id,
                        referer
                    ).forEach(callback)
                }
                return true
            }
            
            // Try to get embed URL through post request
            val embedResponse = try {
                val body = FormBody.Builder()
                    .addEncoded("action", "doo_player_ajax")
                    .addEncoded("post", id)
                    .addEncoded("nume", "1")
                    .addEncoded("type", "movie")
                    .build()

                app.post(
                    "$mainUrl/wp-admin/admin-ajax.php",
                    requestBody = body,
                    referer = referer
                )
            } catch (e: Exception) {
                return false
            }
            
            val embedUrl = try {
                val embedData = embedResponse.parsed<EmbedUrl>()
                fixUrlNull(embedData.embedUrl) ?: return false
            } catch (e: Exception) {
                // If parsing fails, try to extract URL directly from response
                val regex = Regex("iframe.*?src=[\"'](.*?)[\"']")
                val match = regex.find(embedResponse.text)
                match?.groupValues?.get(1) ?: return false
            }
            
            // Try the extractor system first
            loadExtractor(embedUrl, referer, subtitleCallback, callback)
            
            // Handle iframe content
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
                val m3u8Regex = Regex("['\"](https?://[^'\"]+\\.m3u8[^'\"]*)['\"]")
                val m3u8Urls = m3u8Regex.findAll(scriptData).map { it.groupValues[1] }.toList()
                
                // Combine all URLs and handle them
                (sourceUrls + m3u8Urls).filter { it.isNotBlank() }.distinct().forEach { url ->
                    if (url.contains(".m3u8")) {
                        M3u8Helper.generateM3u8(
                            name,
                            url,
                            embedUrl
                        ).forEach(callback)
                    }
                }
                
                // Check for direct stream in iframe attrs
                val iframeSrc = iframeDoc.select("iframe").attr("src")
                if (iframeSrc.isNotBlank() && iframeSrc != embedUrl) {
                    loadExtractor(iframeSrc, embedUrl, subtitleCallback, callback)
                }
            } catch (e: Exception) {
                // Try direct handling as fallback
                if (embedUrl.contains(".m3u8")) {
                    M3u8Helper.generateM3u8(
                        name,
                        embedUrl,
                        referer
                    ).forEach(callback)
                }
            }
            
            return true
        } catch (e: Exception) {
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

