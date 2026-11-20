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
import com.lagradost.cloudstream3.utils.StringUtils.encodeUri
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream

class MiruroProvider : MainAPI() {

    override var mainUrl = "https://www.miruro.cx"
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

    // Cloudflare interceptor using Android WebView to bypass Cloudflare Turnstile if encountered
    private val webViewResolver by lazy {
        WebViewResolver(
            interceptUrl = Regex("""https?://(?:www\.)?miruro\.(?:cx|to|tv|bz).*"""),
            additionalUrls = listOf(
                Regex(""".*challenges\.cloudflare\.com/.*"""),
                Regex(""".*keeply\.top/.*"""),
                Regex(""".*strm\.cx/.*"""),
                Regex(""".*quavex\.top/.*""")
            ),
            userAgent = USER_AGENT
        )
    }

    private val defaultHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "$mainUrl/",
        "Accept" to "*/*",
        "Accept-Language" to "en-US,en;q=0.9",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "same-origin"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/trending" to "Trending Anime",
        "$mainUrl/search?sort=-popularity" to "Most Popular",
        "$mainUrl/schedule" to "Airing Schedule",
        "$mainUrl/search?sort=-score" to "Top Rated"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = mutableListOf<SearchResponse>()

        // 1. Try Miruro direct API first
        val apiPath = when {
            request.data.contains("trending") -> "$mainUrl/api/v1/anime/trending?limit=20"
            request.data.contains("popularity") -> "$mainUrl/api/v1/anime?sort=-popularity&limit=20"
            request.data.contains("score") -> "$mainUrl/api/v1/anime?sort=-score&limit=20"
            else -> "$mainUrl/api/v1/anime?limit=20"
        }

        try {
            val resp = app.get(apiPath, headers = defaultHeaders, interceptor = webViewResolver)
            if (resp.code == 200) {
                val contentType = resp.headers["content-type"] ?: ""
                val text = if (contentType.contains("octet-stream", ignoreCase = true)) {
                    decryptCatalog(resp.body.bytes())
                } else {
                    resp.text
                }

                if (text.trim().startsWith("{") || text.trim().startsWith("[")) {
                    val parsed = parseAnimeJsonList(text)
                    if (parsed.isNotEmpty()) {
                        return newHomePageResponse(
                            listOf(HomePageList(request.name, parsed, isHorizontalImages = false)),
                            hasNext = false
                        )
                    }
                }
            }
        } catch (_: Throwable) {
            // Fallback to HTML parsing
        }

        // 2. Fallback to HTML LD-JSON or link parsing
        try {
            val doc = app.get(request.data, headers = defaultHeaders, interceptor = webViewResolver).document
            val parsedHtml = parseLdJsonItems(doc)
            if (parsedHtml.isNotEmpty()) {
                items.addAll(parsedHtml)
            } else {
                items.addAll(parseHtmlCards(doc))
            }
        } catch (_: Throwable) {}

        return newHomePageResponse(
            listOf(HomePageList(request.name, items, isHorizontalImages = false)),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()

        // 1. Try Miruro search API
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        try {
            val apiUrl = "$mainUrl/api/v1/anime?query=$encoded&limit=20"
            val resp = app.get(apiUrl, headers = defaultHeaders, interceptor = webViewResolver)
            if (resp.code == 200) {
                val contentType = resp.headers["content-type"] ?: ""
                val text = if (contentType.contains("octet-stream", ignoreCase = true)) {
                    decryptCatalog(resp.body.bytes())
                } else {
                    resp.text
                }
                val parsed = parseAnimeJsonList(text)
                if (parsed.isNotEmpty()) return parsed
            }
        } catch (_: Throwable) {}

        // 2. Fallback to search HTML page
        try {
            val htmlUrl = "$mainUrl/search?query=$encoded"
            val doc = app.get(htmlUrl, headers = defaultHeaders, interceptor = webViewResolver).document
            val ldItems = parseLdJsonItems(doc)
            if (ldItems.isNotEmpty()) return ldItems
            return parseHtmlCards(doc)
        } catch (_: Throwable) {}

        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val animeId = extractAnimeId(url)

        // 1. Try API first if we have an ID
        if (animeId != null) {
            try {
                val detailUrl = "$mainUrl/api/v1/anime/$animeId"
                val resp = app.get(detailUrl, headers = defaultHeaders, interceptor = webViewResolver)
                if (resp.code == 200) {
                    val contentType = resp.headers["content-type"] ?: ""
                    val text = if (contentType.contains("octet-stream", ignoreCase = true)) {
                        decryptCatalog(resp.body.bytes())
                    } else {
                        resp.text
                    }
                    val anime = mapper.readValue<MiruroAnimeDetail>(text)
                    return buildAnimeLoadResponse(anime, url)
                }
            } catch (_: Throwable) {}
        }

        // 2. Fetch HTML page
        val doc = try {
            app.get(url, headers = defaultHeaders, interceptor = webViewResolver).document
        } catch (_: Throwable) {
            return null
        }

        // Try extracting embedded SSR data first (Miruro embeds complete anime JSON in script tag)
        val ssrRegex = Regex("""window\.__SSR_DATA__\s*=\s*["']([^"']+)["']""")
        val ssrMatch = ssrRegex.find(doc.html())
        if (ssrMatch != null) {
            try {
                val b64 = ssrMatch.groupValues[1]
                val decoded = base64Decode(b64)
                val json = decryptCatalog(decoded)
                val anime = mapper.readValue<MiruroAnimeDetail>(json)
                return buildAnimeLoadResponse(anime, url)
            } catch (_: Throwable) {}
        }

        // 3. Fallback to HTML meta tags
        val rawTitle = doc.selectFirst("h1, meta[property='og:title']")?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text()
        } ?: return null

        val title = rawTitle.replace(Regex("""(?i)\s*[·\-|]\s*Miruro.*"""), "").trim()
        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
        val plot = doc.selectFirst("meta[name='description'], meta[property='og:description']")?.attr("content")

        val episodes = mutableListOf<Episode>()
        val epElements = doc.select("a[href*='/watch/'], a[href*='/info/']")
        if (epElements.isNotEmpty()) {
            epElements.forEachIndexed { idx, elem ->
                val epHref = elem.attr("href").trim()
                if (epHref.contains("/watch/")) {
                    val epNum = idx + 1
                    episodes.add(
                        newEpisode(fixUrl(epHref)) {
                            this.name = "Episode $epNum"
                            this.episode = epNum
                        }
                    )
                }
            }
        }

        if (episodes.isEmpty()) {
            val watchUrl = if (url.contains("/info/")) url.replace("/info/", "/watch/") else url
            episodes.add(
                newEpisode(watchUrl) {
                    this.name = title
                    this.episode = 1
                }
            )
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            addEpisodes(DubStatus.Subbed, episodes)
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

        // 2. Playback payload (JSON containing animeId and episodeId)
        if (data.startsWith("{") && data.contains("animeId")) {
            try {
                val payload = mapper.readValue<MiruroPlayPayload>(data)
                val playUrl = "$mainUrl/api/v1/anime/${payload.animeId}/episodes/${payload.episodeId}/play"
                val resp = app.get(playUrl, headers = defaultHeaders, interceptor = webViewResolver)
                if (resp.code == 200) {
                    val contentType = resp.headers["content-type"] ?: ""
                    val text = if (contentType.contains("octet-stream", ignoreCase = true)) {
                        decryptCatalog(resp.body.bytes())
                    } else {
                        resp.text
                    }

                    val playData = mapper.readValue<MiruroPlayResponse>(text)

                    // Extract subtitles from playback response
                    for (sub in playData.subtitles.orEmpty()) {
                        val subFile = sub.file ?: continue
                        val finalSubUrl = if (subFile.startsWith("http")) {
                            subFile
                        } else {
                            buildKeeplyProxyUrl(subFile, "$mainUrl/", "sub.vtt")
                        }
                        subtitleCallback.invoke(
                            newSubtitleFile(
                                lang = sub.label ?: sub.language ?: "English",
                                url = finalSubUrl
                            )
                        )
                    }

                    for (track in playData.tracks.orEmpty()) {
                        for (prov in track.providers.orEmpty()) {
                            for (server in prov.servers.orEmpty()) {
                                // Extract server level subtitles if available
                                for (sub in server.subtitles.orEmpty()) {
                                    val subFile = sub.file ?: continue
                                    val finalSubUrl = if (subFile.startsWith("http")) {
                                        subFile
                                    } else {
                                        buildKeeplyProxyUrl(subFile, "$mainUrl/", "sub.vtt")
                                    }
                                    subtitleCallback.invoke(
                                        newSubtitleFile(
                                            lang = sub.label ?: sub.language ?: "English",
                                            url = finalSubUrl
                                        )
                                    )
                                }

                                for (stream in server.streams.orEmpty()) {
                                    val streamUrl = stream.url
                                    if (!streamUrl.isNullOrBlank()) {
                                        val finalUrl = if (server.proxy == true || prov.proxy == true) {
                                            buildKeeplyProxyUrl(streamUrl, "$mainUrl/", "pl.m3u8")
                                        } else {
                                            streamUrl
                                        }

                                        try {
                                            M3u8Helper.generateM3u8(
                                                source = "$name - ${server.server ?: prov.provider ?: "HD"} (${track.track ?: "sub"})",
                                                streamUrl = finalUrl,
                                                referer = "$mainUrl/"
                                            ).forEach {
                                                callback.invoke(it)
                                                foundAny = true
                                            }
                                        } catch (_: Throwable) {
                                            callback.invoke(
                                                newExtractorLink(
                                                    source = name,
                                                    name = "$name - ${server.server ?: "HD"}",
                                                    url = finalUrl,
                                                    type = ExtractorLinkType.M3U8
                                                ) {
                                                    this.referer = "$mainUrl/"
                                                    this.quality = Qualities.P1080.value
                                                }
                                            )
                                            foundAny = true
                                        }
                                    }

                                    val embed = stream.embed ?: server.embed
                                    if (embed?.url != null) {
                                        try {
                                            if (loadExtractor(embed.url, "$mainUrl/", subtitleCallback, callback)) {
                                                foundAny = true
                                            }
                                        } catch (_: Throwable) {}
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (_: Throwable) {}
        }

        // 3. Fallback: inspect watch page iframes
        val watchUrl = if (data.startsWith("http")) data else "$mainUrl$data"
        try {
            val doc = app.get(watchUrl, headers = defaultHeaders, interceptor = webViewResolver).document
            val iframes = doc.select("iframe[src]").mapNotNull { it.attr("src") }
            for (iframe in iframes) {
                if (iframe.isNotBlank() && !iframe.startsWith("javascript:")) {
                    try {
                        if (loadExtractor(fixUrl(iframe), watchUrl, subtitleCallback, callback)) {
                            foundAny = true
                        }
                    } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {}

        return foundAny
    }

    // Helper parsers

    private fun extractAnimeId(url: String): String? {
        val regex = Regex("""/(?:info|watch)/([a-zA-Z0-9_\-]+)""")
        return regex.find(url)?.groupValues?.get(1)
    }

    private fun parseAnimeJsonList(jsonText: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        try {
            val root = mapper.readTree(jsonText)
            val items = if (root.isArray) root else root.get("data") ?: root.get("items") ?: root
            if (items != null && items.isArray) {
                for (node in items) {
                    val id = node.get("id")?.asText() ?: continue
                    val titleNode = node.get("title")
                    val title = titleNode?.get("english")?.asText()
                        ?: titleNode?.get("romaji")?.asText()
                        ?: titleNode?.get("native")?.asText()
                        ?: node.get("name")?.asText()
                        ?: continue

                    val poster = node.get("cover_image")?.asText()
                        ?: node.get("cover_url")?.asText()
                        ?: node.get("poster")?.asText()

                    val format = node.get("format")?.asText()
                    val type = getType(format)

                    val animeUrl = "$mainUrl/info/$id"
                    results.add(
                        newAnimeSearchResponse(title, animeUrl, type) {
                            this.posterUrl = poster
                        }
                    )
                }
            }
        } catch (_: Throwable) {}
        return results
    }

    private fun parseLdJsonItems(doc: Document): List<SearchResponse> {
        val list = mutableListOf<SearchResponse>()
        val scripts = doc.select("script[type='application/ld+json']")
        for (script in scripts) {
            try {
                val tree = mapper.readTree(script.data())
                val elements = when {
                    tree.has("itemListElement") -> tree.get("itemListElement")
                    tree.isArray -> tree
                    else -> null
                }
                if (elements != null && elements.isArray) {
                    for (el in elements) {
                        val name = el.get("name")?.asText()
                        val itemUrl = el.get("url")?.asText() ?: el.get("item")?.asText()
                        if (!name.isNullOrBlank() && !itemUrl.isNullOrBlank() && itemUrl.contains("/info/")) {
                            list.add(
                                newAnimeSearchResponse(name, fixUrl(itemUrl), TvType.Anime)
                            )
                        }
                    }
                }
            } catch (_: Throwable) {}
        }
        return list
    }

    private fun parseHtmlCards(doc: Document): List<SearchResponse> {
        val list = mutableListOf<SearchResponse>()
        val links = doc.select("a[href*='/info/']")
        for (link in links) {
            val href = link.attr("href").trim()
            val title = link.attr("title").ifBlank { link.text().trim() }
            val poster = link.selectFirst("img")?.let { it.attr("src").ifBlank { it.attr("data-src") } }
            if (title.isNotBlank() && href.isNotBlank()) {
                list.add(
                    newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) {
                        this.posterUrl = poster
                    }
                )
            }
        }
        return list.distinctBy { it.url }
    }

    private suspend fun buildAnimeLoadResponse(anime: MiruroAnimeDetail, originalUrl: String): AnimeLoadResponse {
        val title = anime.title?.english ?: anime.title?.romaji ?: anime.title?.native ?: "Anime"
        val type = getType(anime.format)
        val episodes = mutableListOf<Episode>()

        // Fetch episodes from Miruro episodes endpoint
        try {
            val epsUrl = "$mainUrl/api/v1/anime/${anime.id}/episodes"
            val resp = app.get(epsUrl, headers = defaultHeaders, interceptor = webViewResolver)
            if (resp.code == 200) {
                val contentType = resp.headers["content-type"] ?: ""
                val text = if (contentType.contains("octet-stream", ignoreCase = true)) {
                    decryptCatalog(resp.body.bytes())
                } else {
                    resp.text
                }
                val epList = mapper.readValue<List<MiruroEpisodeItem>>(text)
                epList.forEachIndexed { idx, epItem ->
                    val epNumber = epItem.episodeNumber ?: (idx + 1)
                    val epName = epItem.title ?: "Episode $epNumber"
                    val payload = MiruroPlayPayload(
                        animeId = anime.id,
                        episodeId = epItem.id ?: epNumber.toString()
                    )
                    episodes.add(
                        newEpisode(mapper.writeValueAsString(payload)) {
                            this.name = epName
                            this.episode = epNumber
                            this.posterUrl = epItem.thumbnail
                        }
                    )
                }
            }
        } catch (_: Throwable) {}

        if (episodes.isEmpty()) {
            val total = anime.episodeCount ?: 1
            for (i in 1..total) {
                val payload = MiruroPlayPayload(animeId = anime.id, episodeId = i.toString())
                episodes.add(
                    newEpisode(mapper.writeValueAsString(payload)) {
                        this.name = "Episode $i"
                        this.episode = i
                    }
                )
            }
        }

        return newAnimeLoadResponse(title, originalUrl, type) {
            this.posterUrl = anime.coverUrl ?: anime.coverImage
            this.backgroundPosterUrl = anime.bannerUrl ?: anime.backgroundUrl
            this.plot = anime.description
            this.year = anime.seasonYear ?: anime.startedOn?.take(4)?.toIntOrNull()
            this.tags = anime.genres
            this.showStatus = getStatus(anime.status)
            addEpisodes(DubStatus.Subbed, episodes)

            if (anime.anilistId != null) {
                addAniListId(anime.anilistId)
            } else {
                anime.externalIds?.get("anilist")?.firstOrNull()?.toIntOrNull()?.let { addAniListId(it) }
            }

            if (anime.malId != null) {
                addMalId(anime.malId)
            } else {
                anime.externalIds?.get("mal")?.firstOrNull()?.toIntOrNull()?.let { addMalId(it) }
            }

            val trailerId = anime.trailerYoutubeId
            if (!trailerId.isNullOrBlank()) {
                addTrailer("https://www.youtube.com/watch?v=$trailerId")
            }
        }
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
