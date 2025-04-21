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
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

class TamilUltraProvider : MainAPI() {
    override var mainUrl = "https://tamilultra.fr"
    override var name = "TamilUltra"
    override val hasMainPage = true
    override var lang = "ta"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Live
    )

    // Channel name to direct stream URL mappings (fallbacks)
    private val directStreamMap = mapOf(
        "Star Sports 1" to "https://starshare.live/live/starsports1/playlist.m3u8",
        "Star Sports 2" to "https://starshare.live/live/starsports2/playlist.m3u8",
        "Star Sports 3" to "https://starshare.live/live/starsports3/playlist.m3u8",
        "Star Sports Select 1" to "https://starshare.live/live/select1/playlist.m3u8",
        "Star Sports Select 2" to "https://starshare.live/live/select2/playlist.m3u8",
        "Sony Ten 1" to "https://dai.google.com/linear/hls/event/wG75n5U8RrOKiFzaWObXbA/master.m3u8",
        "Sony Ten 2" to "https://dai.google.com/linear/hls/event/V9h-iyOxRiGp41ppQScDSQ/master.m3u8",
        "Sony Ten 3" to "https://dai.google.com/linear/hls/event/ltsCG7TBSCSDmyq0rQtvSA/master.m3u8",
        "Sony Ten 4" to "https://dai.google.com/linear/hls/event/smYybI_JToWaHzwoxSE9qA/master.m3u8",
        "Sony Ten 5" to "https://dai.google.com/linear/hls/event/Sle_TR8rQIuZHWzshEXYjQ/master.m3u8",
        "Sony Max HD" to "https://dai.google.com/linear/hls/event/UcjHNJmCQ1WRlGKlZm73QA/master.m3u8",
        "Sony Sab HD" to "https://dai.google.com/linear/hls/event/CrTivkDESWqwvUj3zFEYEA/master.m3u8",
        "Sony Entertainment" to "https://dai.google.com/linear/hls/event/dBdwOiGaQvy0TA1zOsjV6w/master.m3u8",
        "Zee TV HD" to "https://d75dqofg5kmfk.cloudfront.net/bpk-tv/Zeetvhd/default/index.m3u8",
        "Star Plus HD" to "https://starshare.live/live/starplus/playlist.m3u8",
        "Colors HD" to "https://prod-sports-north-gm.jiocinema.com/bpk-tv/Colors_HD_voot_MOB/Fallback/index.m3u8",
        "Discovery HD" to "https://varun-iptv.netlify.app/m3u/discoveryhindi.m3u8",
        "Sun TV" to "https://suntvlive.akamaized.net/hls/live/2093448/SunTV/master.m3u8",
        "KTV HD" to "https://suntvlive.akamaized.net/hls/live/2093446/KTVHD/master.m3u8",
        "Adithya" to "https://suntvlive.akamaized.net/hls/live/2093453/AdithyaTV/master.m3u8",
        "Jaya TV" to "https://livehub.udx.workers.dev/index.php?c=jayatv",
        "Zee Tamil" to "https://d75dqofg5kmfk.cloudfront.net/bpk-tv/Zeetamil/default/index.m3u8",
        "Colors Tamil" to "https://prod-sports-north-gm.jiocinema.com/bpk-tv/Colors_Tamil_HD_voot_MOB/Fallback/index.m3u8",
        "Polimer" to "https://livehub.udx.workers.dev/index.php?c=polimer",
        "Puthiya Thalaimurai" to "https://livehub.udx.workers.dev/index.php?c=ptlive",
        "Thanthi TV" to "https://vidcdn.vidgyor.com/thanthi-origin/liveabr/thanthi-origin/live_720p/chunks.m3u8",
        "News 7" to "https://livehub.udx.workers.dev/index.php?c=news7tamil",
        "News 18 Tamil" to "https://livehub.udx.workers.dev/index.php?c=news18tamil",
        "ETV Telangana" to "https://livehub.udx.workers.dev/index.php?c=etvtelangana",
        "ETV Andhra" to "https://livehub.udx.workers.dev/index.php?c=etvandhra",
        "TV9 Telugu" to "https://livehub.udx.workers.dev/index.php?c=tv9telugu",
        "NTV Telugu" to "https://livehub.udx.workers.dev/index.php?c=ntvtelugu",
        "ABN Telugu" to "https://livehub.udx.workers.dev/index.php?c=abntelugu",
        "Sakshi TV" to "https://livehub.udx.workers.dev/index.php?c=sakshitv",
        "ETV Kannada" to "https://livehub.udx.workers.dev/index.php?c=etvkannada",
        "TV9 Kannada" to "https://livehub.udx.workers.dev/index.php?c=tv9kannada",
        "Asianet" to "https://d75dqofg5kmfk.cloudfront.net/bpk-tv/Asianet/default/index.m3u8",
        "Surya TV" to "https://livehub.udx.workers.dev/index.php?c=suryatv",
        "Mazhavil Manorama" to "https://livehub.udx.workers.dev/index.php?c=mazhavilmanorama",
        "Zee Keralam" to "https://d75dqofg5kmfk.cloudfront.net/bpk-tv/Zeekeralam/default/index.m3u8"
    )

    // This keeps track of player options
    private var playerOptions = mutableListOf<Pair<String, String>>()

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
        
        // Get all player options and store them
        playerOptions.clear()
        doc.select("[id^=player-option-]").forEach { 
            val postId = it.attr("data-post")
            val nume = it.attr("data-nume")
            playerOptions.add(Pair(postId, nume))
        }

        return newMovieLoadResponse(title, id, TvType.Live, "$url,$id,$title") {
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
            // Parse data
            val dataSplit = data.split(",")
            val referer = dataSplit[0]
            val id = dataSplit[1]
            val channelTitle = if (dataSplit.size > 2) dataSplit[2] else null
            
            // Try getting stream from the main player option first
            if (!tryPlayerOption(id, "1", referer, callback)) {
                // If main player failed, try other player options
                var streamFound = false
                playerOptions.forEach { (optionId, optionNume) ->
                    if (optionId != id || optionNume != "1") {
                        if (tryPlayerOption(optionId, optionNume, referer, callback)) {
                            streamFound = true
                            return@forEach
                        }
                    }
                }
                
                if (!streamFound) {
                    return false
                }
            }
            
            return true
        } catch (e: Exception) {
            Log.e("TamilUltra", "General error: ${e.message}")
            return false
        }
    }
    
    private suspend fun tryPlayerOption(
        id: String,
        nume: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            // Get embed response
            val embedResponse = getEmbed(id, nume, referer)
            val embedData = try {
                embedResponse.parsed<EmbedUrl>()
            } catch (e: Exception) {
                Log.e("TamilUltra", "Error parsing embed URL: ${e.message}")
                return false
            }
            
            val embedUrl = fixUrlNull(embedData.embedUrl) ?: return false
            Log.d("TamilUltra", "Embed URL: $embedUrl")
            
            // Check if the embedUrl is from the tamilultra.fr domain or a known embed source
            if (!isValidUrl(embedUrl)) {
                Log.w("TamilUltra", "Skipping non-tamilultra embed: $embedUrl")
                return false
            }
            
            // Try loadExtractor first for known sources
            if (isKnownEmbedSource(embedUrl)) {
                loadExtractor(embedUrl, referer, subtitleCallback = { }, callback)
            }
            
            // Try direct stream extraction from tamilultra pages
            val streamUrls = mutableListOf<String>()
            
            try {
                val iframeResp = app.get(
                    embedUrl, 
                    referer = referer,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Accept" to "*/*",
                        "Origin" to mainUrl
                    )
                )
                
                val iframeHtml = iframeResp.text
                
                // Look for stream patterns in the HTML content
                val m3u8Patterns = listOf(
                    "['\"](https?://[^'\"]+?\\.m3u8[^'\"]*?)['\"]",
                    "['\"]([^'\"]+?\\.m3u8[^'\"]*?)['\"]",
                    "=['\"]([^'\"]*?/(?:stream|hls|m3u8|playlist|index)[^'\"]*?\\.m3u8[^'\"]*?)['\"]",
                    "source:?\\s*['\"](https?://[^'\"]+)['\"]",
                    "file:?\\s*['\"](https?://[^'\"]+)['\"]",
                    "src:?\\s*['\"](https?://[^'\"]+)['\"]"
                )
                
                m3u8Patterns.forEach { pattern ->
                    Regex(pattern).findAll(iframeHtml).forEach { match ->
                        val url = match.groupValues.lastOrNull { 
                            it.isNotBlank() && (it.contains(".m3u8") || it.contains("/stream") || it.contains("/hls")) 
                        }
                        if (url != null && url.isNotBlank()) {
                            streamUrls.add(url)
                        }
                    }
                }
                
                // Search for any iframe sources within tamilultra
                Regex("<iframe[^>]*?src=['\"]([^'\"]+?)['\"][^>]*?>").findAll(iframeHtml).forEach { match ->
                    val iframeSrc = match.groupValues.getOrNull(1)
                    if (!iframeSrc.isNullOrBlank() && 
                        (iframeSrc.contains(mainUrl.removePrefix("https://")) || 
                         iframeSrc.startsWith("/") || 
                         isKnownEmbedSource(iframeSrc))) {
                        
                        val fullUrl = when {
                            iframeSrc.startsWith("//") -> "https:$iframeSrc"
                            iframeSrc.startsWith("/") -> "$mainUrl$iframeSrc"
                            !iframeSrc.startsWith("http") -> "$mainUrl/$iframeSrc"
                            else -> iframeSrc
                        }
                        
                        try {
                            val nestedResp = app.get(
                                fullUrl, 
                                referer = embedUrl,
                                headers = mapOf(
                                    "User-Agent" to USER_AGENT
                                )
                            )
                            
                            val nestedHtml = nestedResp.text
                            
                            m3u8Patterns.forEach { pattern ->
                                Regex(pattern).findAll(nestedHtml).forEach { nestedMatch ->
                                    val url = nestedMatch.groupValues.lastOrNull { 
                                        it.isNotBlank() && (it.contains(".m3u8") || it.contains("/stream")) 
                                    }
                                    if (url != null && url.isNotBlank() && isValidStreamUrl(url)) {
                                        streamUrls.add(url)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("TamilUltra", "Error in nested iframe: ${e.message}")
                        }
                    }
                }
                
                // Look for obfuscated/encoded streams
                val base64Regex = Regex("atob\\(['\"](.*?)['\"]\\)")
                base64Regex.findAll(iframeHtml).forEach { match ->
                    try {
                        val encoded = match.groupValues.getOrNull(1)
                        if (!encoded.isNullOrBlank()) {
                            val decoded = java.util.Base64.getDecoder().decode(encoded).toString(Charsets.UTF_8)
                            if (decoded.contains(".m3u8") || decoded.contains("/stream")) {
                                streamUrls.add(decoded)
                            }
                        }
                    } catch (e: Exception) {
                        // Ignore bad base64
                    }
                }
                
                // Check for query parameters in the embed URL that might contain stream links
                if (embedUrl.contains("?")) {
                    val queryParams = embedUrl.substringAfter("?").split("&")
                    for (param in queryParams) {
                        if (param.contains("=")) {
                            val parts = param.split("=", limit = 2)
                            if (parts.size == 2) {
                                val key = parts[0]
                                val value = parts[1]
                                if (listOf("source", "src", "file", "stream", "url", "link", "video").contains(key.lowercase())) {
                                    val decodedValue = try {
                                        URLDecoder.decode(value, "UTF-8")
                                    } catch (e: Exception) {
                                        value
                                    }
                                    
                                    if (decodedValue.isNotBlank() && (decodedValue.contains(".m3u8") || decodedValue.contains("/stream"))) {
                                        streamUrls.add(decodedValue)
                                    }
                                }
                            }
                        }
                    }
                }
                
                // Process and normalize all found stream URLs
                val normalizedUrls = streamUrls.filter { it.isNotBlank() }
                    .map { url ->
                        when {
                            url.startsWith("//") -> "https:$url"
                            url.startsWith("/") -> "$mainUrl$url"
                            !url.startsWith("http") -> {
                                val embedBase = embedUrl.substringBeforeLast("/", "")
                                "$embedBase/$url"
                            }
                            else -> url
                        }
                    }
                    .filter { isValidStreamUrl(it) }
                    .distinct()
                
                Log.d("TamilUltra", "Found ${normalizedUrls.size} potential stream URLs")
                
                // Try each stream URL
                var streamFound = false
                normalizedUrls.forEach { url ->
                    try {
                        Log.d("TamilUltra", "Trying stream URL: $url")
                        M3u8Helper.generateM3u8(
                            name,
                            url,
                            embedUrl,
                            headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to embedUrl,
                                "Origin" to getBaseUrl(embedUrl)
                            )
                        ).forEach { link ->
                            callback(link)
                            streamFound = true
                        }
                    } catch (e: Exception) {
                        Log.e("TamilUltra", "Error with stream URL $url: ${e.message}")
                        // Try direct extraction as fallback
                        if (url.contains(".m3u8") && isValidStreamUrl(url)) {
                            try {
                                callback.invoke(
                                    app.newExtractorLink(
                                        name,
                                        "$name Direct",
                                        url,
                                        embedUrl,
                                        Qualities.Unknown.value,
                                        isM3u8 = true
                                    )
                                )
                                streamFound = true
                            } catch (e2: Exception) {
                                Log.e("TamilUltra", "Error with direct extraction: ${e2.message}")
                            }
                        }
                    }
                }
                
                return streamFound
            } catch (e: Exception) {
                Log.e("TamilUltra", "Main extraction error: ${e.message}")
                return false
            }
        } catch (e: Exception) {
            Log.e("TamilUltra", "Player option error: ${e.message}")
            return false
        }
    }
    
    private fun isValidUrl(url: String): Boolean {
        // Allow tamilultra.fr domain or known embed sources
        return url.contains(mainUrl.removePrefix("https://")) || isKnownEmbedSource(url)
    }
    
    private fun isKnownEmbedSource(url: String): Boolean {
        val knownEmbedDomains = listOf(
            "dailymotion.com", "vimeo.com", "youtube.com", "youtu.be", "player.vimeo.com",
            "drive.google.com", "docs.google.com", "streamable.com", "tune.pk", "mp4upload.com",
            "ok.ru", "vk.com", "brightcove.net", "facebook.com", "fb.watch", "jwplatform.com",
            "jwplayer.com", "jwpsrv.com", "bitchute.com", "rapidvideo.com", "vidoza.net",
            "streamango.com", "openload.co", "veoh.com", "mediafire.com", "players.brightcove.net"
        )
        
        return knownEmbedDomains.any { url.contains(it) }
    }
    
    private fun isValidStreamUrl(url: String): Boolean {
        // Allow tamilultra.fr URLs or URLs that don't have domains (relative paths)
        if (url.contains(mainUrl.removePrefix("https://")) || !url.contains("://")) return true
        
        // Allow common CDN and streaming domains 
        val validStreamDomains = listOf(
            "cloudfront.net", "akamaized.net", "amazonaws.com", "cdnjs.com", "jwpsrv.com",
            "jwpcdn.com", "jwplayer.com", "jwplatform.com", "googlevideo.com", 
            "fastly.net", "bitmovin.com", "akamai.net", "cdn.ampproject.org",
            "cdn.jwplayer.com", "cdn.plyr.io", "hlsjs.video-dev.org", "cloudflare.com"
        )
        
        return validStreamDomains.any { url.contains(it) }
    }
    
    private fun getBaseUrl(url: String): String {
        val httpsPrefix = "https://"
        val httpPrefix = "http://"
        val startIndex = when {
            url.startsWith(httpsPrefix) -> httpsPrefix.length
            url.startsWith(httpPrefix) -> httpPrefix.length
            else -> 0
        }
        val endIndex = url.indexOf('/', startIndex).takeIf { it != -1 } ?: url.length
        return url.substring(0, endIndex)
    }
    
    private fun normalizeTitle(title: String): String {
        return title.lowercase()
            .replace(Regex("\\s+"), "")
            .replace(Regex("[^a-z0-9]"), "")
    }
    
    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36"
    }
}

