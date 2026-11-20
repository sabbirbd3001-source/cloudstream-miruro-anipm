package com.miruro

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream

class MiruroProvider : MainAPI() {

    override var mainUrl = "https://miruro.cx"
    override var name = "Miruro"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private val mapper = jacksonObjectMapper().apply {
            configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        }

        const val PROXY_OBF_KEY = "a54d389c18527d9fd3e7f0643e27edbe"
        const val PROXY_SERVER = "https://s1.keeply.top/"
        const val CATALOG_KEY = "miruro/catalog"

        fun getType(typeString: String?): TvType {
            if (typeString == null) return TvType.Anime
            val lower = typeString.lowercase()
            return when {
                lower.contains("movie") -> TvType.AnimeMovie
                lower.contains("ova") || lower.contains("special") -> TvType.OVA
                else -> TvType.Anime
            }
        }

        fun getStatus(statusString: String?): ShowStatus {
            if (statusString == null) return ShowStatus.Ongoing
            val lower = statusString.lowercase()
            return when {
                lower.contains("completed") || lower.contains("finished") -> ShowStatus.Completed
                else -> ShowStatus.Ongoing
            }
        }

        /**
         * Pure Kotlin Base64 URL-safe encoding without padding.
         * Works consistently across JVM unit tests and Android runtimes.
         */
        fun base64UrlEncode(bytes: ByteArray): String {
            val table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
            val sb = StringBuilder()
            var i = 0
            while (i < bytes.size) {
                val b1 = bytes[i++].toInt() and 0xFF
                if (i < bytes.size) {
                    val b2 = bytes[i++].toInt() and 0xFF
                    if (i < bytes.size) {
                        val b3 = bytes[i++].toInt() and 0xFF
                        sb.append(table[b1 shr 2])
                        sb.append(table[((b1 and 0x03) shl 4) or (b2 shr 4)])
                        sb.append(table[((b2 and 0x0F) shl 2) or (b3 shr 6)])
                        sb.append(table[b3 and 0x3F])
                    } else {
                        sb.append(table[b1 shr 2])
                        sb.append(table[((b1 and 0x03) shl 4) or (b2 shr 4)])
                        sb.append(table[(b2 and 0x0F) shl 2])
                    }
                } else {
                    sb.append(table[b1 shr 2])
                    sb.append(table[(b1 and 0x03) shl 4])
                }
            }
            return sb.toString()
        }

        /**
         * Pure Kotlin Base64 decoder supporting standard, URL-safe, and unpadded strings.
         * Runs consistently in JVM tests and Android runtimes without SDK dependencies.
         */
        fun base64Decode(input: String): ByteArray {
            val clean = input.trim().replace("\n", "").replace("\r", "")
            val table = IntArray(128) { -1 }
            val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
            val charsUrl = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
            for (i in chars.indices) {
                table[chars[i].code] = i
                table[charsUrl[i].code] = i
            }
            val out = ByteArrayOutputStream()
            var buffer = 0
            var bitsLeft = 0
            for (c in clean) {
                if (c == '=') break
                val code = c.code
                if (code >= 128) continue
                val v = table[code]
                if (v == -1) continue
                buffer = (buffer shl 6) or v
                bitsLeft += 6
                if (bitsLeft >= 8) {
                    bitsLeft -= 8
                    out.write((buffer shr bitsLeft) and 0xFF)
                }
            }
            return out.toByteArray()
        }

        /**
         * Builds obfuscated Keeply proxy URL used by Miruro frontend:
         * https://s1.keeply.top/{base64url(xor(targetUrl))}~{base64url(xor(referer))}/{routeType}
         */
        fun buildKeeplyProxyUrl(targetUrl: String, referer: String? = null, routeType: String = "pl.m3u8"): String {
            val keyBytes = PROXY_OBF_KEY.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

            fun obf(input: String): String {
                val inputBytes = input.toByteArray(Charsets.UTF_8)
                val out = ByteArray(inputBytes.size)
                for (idx in inputBytes.indices) {
                    out[idx] = (inputBytes[idx].toInt() xor keyBytes[idx % keyBytes.size].toInt()).toByte()
                }
                return base64UrlEncode(out)
            }

            val encTarget = obf(targetUrl)
            val encRef = if (!referer.isNullOrBlank()) "~${obf(referer)}" else ""
            val base = if (PROXY_SERVER.endsWith("/")) PROXY_SERVER else "$PROXY_SERVER/"
            val cleanType = routeType.removePrefix("/")
            return "$base$encTarget$encRef/$cleanType"
        }

        /**
         * Decrypts XOR encrypted & GZIP compressed catalog response from Miruro API
         */
        fun decryptCatalog(bytes: ByteArray): String {
            val key = CATALOG_KEY.toByteArray(Charsets.UTF_8)
            val decrypted = ByteArray(bytes.size)
            for (i in bytes.indices) {
                decrypted[i] = (bytes[i].toInt() xor key[i % key.size].toInt()).toByte()
            }
            return try {
                GZIPInputStream(ByteArrayInputStream(decrypted)).bufferedReader(Charsets.UTF_8).use { it.readText() }
            } catch (_: Throwable) {
                String(decrypted, Charsets.UTF_8)
            }
        }
    }

    private val webViewResolver by lazy {
        WebViewResolver(
            interceptUrl = Regex("""https?://(?:www\.)?miruro\.(?:cx|to|tv|bz).*"""),
            additionalUrls = listOf(
                Regex(""".*challenges\.cloudflare\.com/.*"""),
                Regex(""".*keeply\.top/.*"""),
                Regex(""".*strm\.cx/.*"""),
                Regex(""".*quavex\.top/.*"""),
                Regex(""".*ultracloud\.cc/.*"""),
                Regex(""".*megaplay\.buzz/.*""")
            ),
            userAgent = USER_AGENT
        )
    }

    private val defaultHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "$mainUrl/",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "same-origin"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/trending" to "Trending Anime",
        "$mainUrl/search?sort=-popularity" to "Most Popular",
        "$mainUrl/schedule" to "Airing Schedule",
        "$mainUrl/search?sort=-score" to "Top Rated"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val doc = try {
            app.get(request.data, headers = defaultHeaders, interceptor = webViewResolver).document
        } catch (_: Throwable) {
            return null
        }

        val items = parseHtmlCards(doc)
        return newHomePageResponse(
            listOf(HomePageList(request.name, items, isHorizontalImages = false)),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()

        val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val searchUrl = "$mainUrl/search?q=$encoded"
        val doc = try {
            app.get(searchUrl, headers = defaultHeaders, interceptor = webViewResolver).document
        } catch (_: Throwable) {
            return emptyList()
        }

        return parseHtmlCards(doc)
    }

    override suspend fun load(url: String): LoadResponse? {
        val animeId = extractAnimeId(url) ?: return null
        val watchUrl = "$mainUrl/watch/$animeId"

        val doc = try {
            app.get(watchUrl, headers = defaultHeaders, interceptor = webViewResolver).document
        } catch (_: Throwable) {
            try {
                app.get(url, headers = defaultHeaders, interceptor = webViewResolver).document
            } catch (_: Throwable) {
                return null
            }
        }

        val rawTitle = doc.selectFirst("meta[property='og:title']")?.attr("content")
            ?: doc.selectFirst("h1")?.text()
            ?: url.split("/").lastOrNull()?.replace("-", " ")
            ?: "Anime"

        val cleanTitle = rawTitle
            .replace(Regex("""(?i)^Watch\s+"""), "")
            .replace(Regex("""(?i)^Stream\s+"""), "")
            .replace(Regex("""(?i)\s*[·\-|]\s*Miruro.*"""), "")
            .trim()

        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
            ?: doc.selectFirst("meta[name='twitter:image']")?.attr("content")
        val banner = doc.selectFirst("meta[name='twitter:image']")?.attr("content")
            ?: poster
        val plot = doc.selectFirst("meta[property='og:description']")?.attr("content")
            ?: doc.selectFirst("meta[name='description']")?.attr("content")

        val html = doc.html()
        val episodes = mutableListOf<Episode>()

        // 1. Extract episode items from embedded SvelteKit page data
        val epPattern = Regex("""\{id:"([^"]*)",number:(\d+),title:"([^"]*)",image:"([^"]*)"""")
        val epMatches = epPattern.findAll(html).toList()

        if (epMatches.isNotEmpty()) {
            epMatches.forEach { match ->
                val epNum = match.groupValues[2].toIntOrNull() ?: 1
                val epTitle = match.groupValues[3].ifBlank { "Episode $epNum" }
                val epThumb = match.groupValues[4].takeIf { it.isNotBlank() }

                val payload = MiruroPlayPayload(animeId = animeId, episodeId = epNum.toString())
                val dataJson = mapper.writeValueAsString(payload)

                episodes.add(
                    newEpisode(dataJson) {
                        this.name = epTitle
                        this.episode = epNum
                        this.posterUrl = epThumb
                    }
                )
            }
        }

        // 2. Fallback to episode_counts or single episode if no regex matches
        if (episodes.isEmpty()) {
            val countPattern = Regex("""episode_counts:\{[^}]*?(?:raw|sub):(\d+)""")
            val totalEps = countPattern.find(html)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""episode_count:(\d+)""").find(html)?.groupValues?.get(1)?.toIntOrNull()
                ?: 1

            for (i in 1..totalEps) {
                val payload = MiruroPlayPayload(animeId = animeId, episodeId = i.toString())
                episodes.add(
                    newEpisode(mapper.writeValueAsString(payload)) {
                        this.name = "Episode $i"
                        this.episode = i
                    }
                )
            }
        }

        return newAnimeLoadResponse(cleanTitle, url, TvType.Anime) {
            this.posterUrl = poster
            this.backgroundPosterUrl = banner
            this.plot = plot
            addEpisodes(DubStatus.Subbed, episodes.distinctBy { it.episode })
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundAny = false

        // 1. Direct stream check
        if (data.contains(".m3u8", ignoreCase = true)) {
            try {
                M3u8Helper.generateM3u8(name, data, "$mainUrl/").forEach {
                    callback.invoke(it)
                    foundAny = true
                }
            } catch (_: Throwable) {}
            return foundAny
        }

        // 2. Determine target watch URL
        val watchUrl = if (data.startsWith("{") && data.contains("animeId")) {
            val payload = try {
                mapper.readValue<MiruroPlayPayload>(data)
            } catch (_: Throwable) { null }
            if (payload != null) {
                "$mainUrl/watch/${payload.animeId}?ep=${payload.episodeId}"
            } else {
                fixUrl(data)
            }
        } else if (data.startsWith("http") || data.startsWith("/")) {
            fixUrl(data)
        } else {
            "$mainUrl/watch/$data"
        }

        // 3. Fetch watch page HTML
        val pageHtml = try {
            app.get(watchUrl, headers = defaultHeaders, interceptor = webViewResolver).text
        } catch (_: Throwable) {
            return false
        }

        // 4. Extract all m3u8 streams embedded in watch page
        val m3u8Regex = Regex("""https?://[^"'\s;,\\)]+\.m3u8[^"'\s;,\\)]*""")
        val m3u8Urls = m3u8Regex.findAll(pageHtml).map { it.value }.toSet()

        val ultracloudHeaders = mapOf(
            "Origin" to "https://ultracloud.cc",
            "Referer" to "https://ultracloud.cc/",
            "User-Agent" to USER_AGENT
        )
        val megaplayHeaders = mapOf(
            "Origin" to "https://megaplay.buzz",
            "Referer" to "https://megaplay.buzz/",
            "User-Agent" to USER_AGENT
        )
        val defaultStreamHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$mainUrl/"
        )

        for (streamUrl in m3u8Urls) {
            val (sourceName, referer, headers) = when {
                streamUrl.contains("willowblueice") ||
                streamUrl.contains("riverpalm") ||
                streamUrl.contains("windstartide") ||
                streamUrl.contains("velvetvault") ||
                streamUrl.contains("leafshoreriver") ||
                streamUrl.contains("ultracloud") -> {
                    Triple("$name - Ultra Stream", "https://ultracloud.cc/", ultracloudHeaders)
                }
                streamUrl.contains("nexabloom") ||
                streamUrl.contains("imgnex") ||
                streamUrl.contains("shiora") ||
                streamUrl.contains("megaplay") -> {
                    Triple("$name - Fast Stream", "https://megaplay.buzz/", megaplayHeaders)
                }
                streamUrl.contains("keeply") -> {
                    Triple("$name - Keeply Stream", "$mainUrl/", defaultStreamHeaders)
                }
                else -> {
                    Triple("$name - Stream", "$mainUrl/", defaultStreamHeaders)
                }
            }

            try {
                M3u8Helper.generateM3u8(
                    source = sourceName,
                    streamUrl = streamUrl,
                    referer = referer,
                    headers = headers
                ).forEach { link ->
                    callback.invoke(link)
                    foundAny = true
                }
            } catch (_: Throwable) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = sourceName,
                        url = streamUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = referer
                        this.headers = headers
                        this.quality = Qualities.P1080.value
                    }
                )
                foundAny = true
            }
        }

        // 5. Extract all subtitles from watch page
        val vttRegex = Regex("""https?://[^"'\s;,\\)]+\.vtt[^"'\s;,\\)]*""")
        val vttUrls = vttRegex.findAll(pageHtml)
            .map { it.value }
            .filterNot { it.contains("preview", ignoreCase = true) || it.contains("thumbnail", ignoreCase = true) }
            .toSet()

        for (vttUrl in vttUrls) {
            val lang = when {
                vttUrl.contains("eng", ignoreCase = true) -> "English"
                vttUrl.contains("spa", ignoreCase = true) -> "Spanish"
                vttUrl.contains("fre", ignoreCase = true) -> "French"
                vttUrl.contains("ger", ignoreCase = true) -> "German"
                vttUrl.contains("rus", ignoreCase = true) -> "Russian"
                vttUrl.contains("por", ignoreCase = true) -> "Portuguese"
                vttUrl.contains("ita", ignoreCase = true) -> "Italian"
                vttUrl.contains("pol", ignoreCase = true) -> "Polish"
                vttUrl.contains("ara", ignoreCase = true) -> "Arabic"
                vttUrl.contains("tha", ignoreCase = true) -> "Thai"
                vttUrl.contains("vie", ignoreCase = true) -> "Vietnamese"
                vttUrl.contains("ind", ignoreCase = true) -> "Indonesian"
                vttUrl.contains("jpn", ignoreCase = true) -> "Japanese"
                vttUrl.contains("hin", ignoreCase = true) -> "Hindi"
                else -> "Subtitle"
            }
            subtitleCallback.invoke(
                newSubtitleFile(lang = lang, url = vttUrl)
            )
        }

        // 6. Fallback: inspect any embedded iframes
        val doc = Jsoup.parse(pageHtml, watchUrl)
        for (iframe in doc.select("iframe[src]")) {
            val src = iframe.attr("src")
            if (src.isNotBlank() && !src.startsWith("javascript:")) {
                try {
                    if (loadExtractor(fixUrl(src), watchUrl, subtitleCallback, callback)) {
                        foundAny = true
                    }
                } catch (_: Throwable) {}
            }
        }

        return foundAny
    }

    // Helper parsers

    fun extractAnimeId(url: String): String? {
        val regex = Regex("""/(?:info|watch)/([a-zA-Z0-9_\-]+)""")
        return regex.find(url)?.groupValues?.get(1)
    }

    fun parseHtmlCards(doc: Document): List<SearchResponse> {
        val list = mutableListOf<SearchResponse>()
        val links = doc.select("a[href*='/info/'], a[href*='/watch/']")
        for (link in links) {
            val href = link.attr("href").trim()
            if (href.isBlank()) continue

            val fullUrl = fixUrl(href)

            var title = link.attr("aria-label").trim()
            if (title.isBlank()) {
                title = link.attr("title").trim()
            }
            val img = link.selectFirst("img")
                ?: link.parent()?.selectFirst("img")
                ?: link.closest("article, div")?.selectFirst("img")

            if (title.isBlank() && img != null) {
                title = img.attr("alt").trim()
            }
            if (title.isBlank()) {
                val heading = link.selectFirst("h1, h2, h3, h4, .title, span")?.text()?.trim()
                if (!heading.isNullOrBlank()) title = heading
            }
            if (title.isBlank()) {
                val slug = href.split("/").lastOrNull()?.takeIf { it.isNotBlank() && !it.startsWith("?") }
                if (slug != null) {
                    title = slug.replace("-", " ")
                        .split(" ")
                        .joinToString(" ") { word -> word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } }
                }
            }

            if (title.isBlank() || title.equals("Miruro", ignoreCase = true)) continue

            val poster = img?.let {
                it.attr("src").ifBlank { it.attr("data-src") }
            }?.takeIf { it.isNotBlank() }

            list.add(
                newAnimeSearchResponse(title, fullUrl, TvType.Anime) {
                    this.posterUrl = poster
                }
            )
        }
        return list.distinctBy { it.url }
    }
}

// Data models for Miruro API

@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroAnimeDetail(
    @JsonProperty("id") val id: String,
    @JsonProperty("title") val title: MiruroTitle? = null,
    @JsonProperty("format") val format: String? = null,
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("cover_image") val coverImage: String? = null,
    @JsonProperty("cover_url") val coverUrl: String? = null,
    @JsonProperty("banner_url") val bannerUrl: String? = null,
    @JsonProperty("background_url") val backgroundUrl: String? = null,
    @JsonProperty("season_year") val seasonYear: Int? = null,
    @JsonProperty("started_on") val startedOn: String? = null,
    @JsonProperty("episode_count") val episodeCount: Int? = null,
    @JsonProperty("genres") val genres: List<String>? = null,
    @JsonProperty("trailer_youtube_id") val trailerYoutubeId: String? = null,
    @JsonProperty("external_ids") val externalIds: Map<String, List<String>>? = null,
    @JsonProperty("anilist_id") val anilistId: Int? = null,
    @JsonProperty("mal_id") val malId: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroTitle(
    @JsonProperty("english") val english: String? = null,
    @JsonProperty("romaji") val romaji: String? = null,
    @JsonProperty("native") val native: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroEpisodeItem(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("episode_number") val episodeNumber: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("thumbnail") val thumbnail: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroPlayPayload(
    val animeId: String,
    val episodeId: String
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroPlayResponse(
    @JsonProperty("tracks") val tracks: List<MiruroTrack>? = null,
    @JsonProperty("subtitles") val subtitles: List<MiruroSubtitle>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroTrack(
    @JsonProperty("track") val track: String? = null,
    @JsonProperty("providers") val providers: List<MiruroPlayProvider>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroPlayProvider(
    @JsonProperty("provider") val provider: String? = null,
    @JsonProperty("proxy") val proxy: Boolean? = null,
    @JsonProperty("servers") val servers: List<MiruroServer>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroServer(
    @JsonProperty("server") val server: String? = null,
    @JsonProperty("proxy") val proxy: Boolean? = null,
    @JsonProperty("embed") val embed: MiruroEmbed? = null,
    @JsonProperty("streams") val streams: List<MiruroStream>? = null,
    @JsonProperty("subtitles") val subtitles: List<MiruroSubtitle>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroStream(
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("format") val format: String? = null,
    @JsonProperty("quality") val quality: String? = null,
    @JsonProperty("embed") val embed: MiruroEmbed? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroEmbed(
    @JsonProperty("url") val url: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroSubtitle(
    @JsonProperty("file") val file: String? = null,
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("language") val language: String? = null,
    @JsonProperty("default") val default: Boolean? = null
)
