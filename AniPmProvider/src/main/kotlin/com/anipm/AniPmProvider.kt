package com.anipm

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
import java.net.URI

class AniPmProvider : MainAPI() {

    override var mainUrl = "https://ani.pm"
    override var name = "Ani.pm"
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
                lower.contains("finished") || lower.contains("completed") -> ShowStatus.Completed
                else -> ShowStatus.Ongoing
            }
        }

        fun fixAniPmUrl(mainUrl: String, path: String?): String? {
            if (path.isNullOrBlank()) return null
            return if (path.startsWith("http://") || path.startsWith("https://")) {
                path
            } else {
                val clean = path.removePrefix("/")
                "$mainUrl/$clean"
            }
        }

        fun extractQueryParam(url: String, param: String): String? {
            return try {
                val query = URI(url).query ?: return null
                val pairs = query.split("&")
                for (pair in pairs) {
                    val idx = pair.indexOf("=")
                    if (idx > 0 && pair.substring(0, idx) == param) {
                        return pair.substring(idx + 1)
                    }
                }
                null
            } catch (_: Throwable) {
                null
            }
        }
    }

    private val webViewResolver by lazy {
        WebViewResolver(
            interceptUrl = Regex("""https?://(?:(?:www\.)?ani\.pm|.*settlar\.io).*"""),
            userAgent = USER_AGENT
        )
    }

    private val defaultHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "$mainUrl/",
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    private suspend fun safeGet(url: String, headers: Map<String, String> = defaultHeaders): com.lagradost.nicehttp.NiceResponse? {
        val direct = try {
            app.get(url, headers = headers)
        } catch (_: Throwable) {
            null
        }
        if (direct != null && direct.code == 200) {
            return direct
        }
        return try {
            app.get(url, headers = headers, interceptor = webViewResolver)
        } catch (_: Throwable) {
            null
        }
    }

    override val mainPage = mainPageOf(
        "spotlight" to "Spotlight",
        "trending" to "Trending Anime",
        "top-today" to "Top Watched Today",
        "top-week" to "Top Watched This Week",
        "movies" to "Top Movies",
        "latest" to "Latest Airing"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val feedUrl = "$mainUrl/api/anime/home-feed?content=3"
        val resp = safeGet(feedUrl, defaultHeaders) ?: return null

        val homeFeed = try {
            mapper.readValue<AniPmHomeFeedResponse>(resp.text)
        } catch (_: Throwable) {
            return null
        }

        val feedMap = homeFeed.feed ?: return null

        val feedKey = when (request.data) {
            "spotlight" -> "/anime/spotlight"
            "trending" -> "/anime/browse?sort=trending&page=1"
            "top-today" -> "/anime/top-watched?range=today&view=rail"
            "top-week" -> "/anime/top-watched?range=week&view=rail"
            "movies" -> "/anime/top-watched?range=all&format=MOVIE&view=rail"
            "latest" -> "/anime/catalog?sort=newest&status=RELEASING&avail=sourced&page=1&view=rail"
            else -> "/anime/browse?sort=trending&page=1"
        }

        val section = feedMap[feedKey]
        val items = section?.items.orEmpty().mapNotNull { item ->
            val id = item.id ?: return@mapNotNull null
            val title = item.title ?: item.native ?: return@mapNotNull null
            val type = getType(item.type)
            val posterUrl = fixAniPmUrl(mainUrl, item.poster)
            val source = item.source ?: "settlar"
            val routeSlug = item.routeId ?: item.slug ?: id.toString()
            val itemUrl = "$mainUrl/anime/$routeSlug?source=$source&id=$id"

            newAnimeSearchResponse(title, itemUrl, type) {
                this.posterUrl = posterUrl
                this.year = item.year
                val dub = item.dubCount ?: 0
                val sub = item.subCount ?: item.episodeCount ?: 0
                addDubStatus(
                    dubExist = dub > 0,
                    subExist = sub > 0,
                    dubEpisodes = if (dub > 0) dub else null,
                    subEpisodes = if (sub > 0) sub else null
                )
            }
        }

        return newHomePageResponse(
            listOf(HomePageList(request.name, items, isHorizontalImages = false)),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()

        val searchUrl = "$mainUrl/api/anime/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val resp = safeGet(searchUrl, defaultHeaders) ?: return emptyList()

        val searchResult = try {
            mapper.readValue<AniPmSearchResponse>(resp.text)
        } catch (_: Throwable) {
            return emptyList()
        }

        return searchResult.items.orEmpty().mapNotNull { item ->
            val id = item.id ?: return@mapNotNull null
            val title = item.title ?: item.native ?: return@mapNotNull null
            val type = getType(item.type)
            val posterUrl = fixAniPmUrl(mainUrl, item.poster)
            val source = item.source ?: "settlar"
            val routeSlug = item.routeId ?: item.slug ?: id.toString()
            val itemUrl = "$mainUrl/anime/$routeSlug?source=$source&id=$id"

            newAnimeSearchResponse(title, itemUrl, type) {
                this.posterUrl = posterUrl
                this.year = item.year
                val dub = item.dubCount ?: 0
                val sub = item.subCount ?: item.episodeCount ?: 0
                addDubStatus(
                    dubExist = dub > 0,
                    subExist = sub > 0,
                    dubEpisodes = if (dub > 0) dub else null,
                    subEpisodes = if (sub > 0) sub else null
                )
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val sourceParam = extractQueryParam(url, "source") ?: "settlar"
        val idParam = extractQueryParam(url, "id")
            ?: Regex("""(?:-(\d+)(?:-[a-zA-Z0-9]+)?|/anime/(\d+))(?:[?#]|$)""").find(url)?.let {
                it.groupValues.getOrNull(1)?.takeIf { v -> v.isNotEmpty() }
                    ?: it.groupValues.getOrNull(2)?.takeIf { v -> v.isNotEmpty() }
            }
            ?: return null

        val apiUrl = if (sourceParam.equals("anilist", ignoreCase = true)) {
            "$mainUrl/api/anime/ani/$idParam"
        } else {
            "$mainUrl/api/anime/series/$idParam"
        }

        val resp = safeGet(apiUrl, defaultHeaders) ?: return null

        if (resp.code != 200) return null

        val detail = try {
            mapper.readValue<AniPmSeriesDetail>(resp.text)
        } catch (_: Throwable) {
            return null
        }

        val title = detail.title ?: detail.native ?: detail.romaji ?: "Anime"
        val type = getType(detail.type)
        val poster = fixAniPmUrl(mainUrl, detail.poster)
        val banner = fixAniPmUrl(mainUrl, detail.banner ?: detail.heroBackdrop)

        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()

        detail.episodes.orEmpty().forEach { ep ->
            val epNum = ep.number ?: 1
            val epName = ep.title ?: "Episode $epNum"
            val thumb = fixAniPmUrl(mainUrl, ep.thumbnail)

            val payload = AniPmPlayPayload(
                source = detail.source ?: sourceParam,
                id = detail.id ?: idParam.toIntOrNull() ?: 0,
                ep = epNum,
                hasSub = ep.sub == true,
                hasDub = ep.dub == true
            )
            val dataString = mapper.writeValueAsString(payload)

            val episodeObj = newEpisode(dataString) {
                this.name = epName
                this.episode = epNum
                this.posterUrl = thumb
                this.description = ep.description
            }

            if (ep.sub == true || ep.sub == null) {
                subEpisodes.add(episodeObj)
            }
            if (ep.dub == true) {
                dubEpisodes.add(episodeObj)
            }
        }

        return newAnimeLoadResponse(title, url, type) {
            this.posterUrl = poster
            this.backgroundPosterUrl = banner
            this.plot = detail.synopsis
            this.year = detail.year
            this.tags = detail.genres
            this.showStatus = getStatus(detail.status)

            if (subEpisodes.isNotEmpty()) {
                addEpisodes(DubStatus.Subbed, subEpisodes)
            }
            if (dubEpisodes.isNotEmpty()) {
                addEpisodes(DubStatus.Dubbed, dubEpisodes)
            } else if (subEpisodes.isEmpty()) {
                // Fallback single episode
                val fallbackPayload = AniPmPlayPayload(
                    source = detail.source ?: sourceParam,
                    id = detail.id ?: idParam.toIntOrNull() ?: 0,
                    ep = 1,
                    hasSub = true,
                    hasDub = false
                )
                addEpisodes(
                    DubStatus.Subbed,
                    listOf(
                        newEpisode(mapper.writeValueAsString(fallbackPayload)) {
                            this.name = title
                            this.episode = 1
                        }
                    )
                )
            }

            if (detail.malId != null) addMalId(detail.malId)
            detail.anilistId?.toIntOrNull()?.let { addAniListId(it) }
            if (!detail.trailer.isNullOrBlank()) {
                addTrailer(detail.trailer)
            }
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

        // 2. Ani.pm Play Payload
        val payload = try {
            mapper.readValue<AniPmPlayPayload>(data)
        } catch (_: Throwable) {
            return false
        }

        // Channels to resolve: Sub, and Dub if available
        val channels = mutableListOf<String>()
        if (payload.hasSub) channels.add("sub")
        if (payload.hasDub) channels.add("dub")
        if (channels.isEmpty()) channels.add("sub")

        for (channel in channels) {
            try {
                // Step 1: Get playback bootstrap to obtain settlarSelection
                val bootUrl = "$mainUrl/api/anime/playback-bootstrap/${payload.source}/${payload.id}?ep=${payload.ep}&lang=$channel"
                val bootResp = safeGet(bootUrl, defaultHeaders) ?: continue
                if (bootResp.code != 200) continue

                val bootData = mapper.readValue<AniPmBootstrapResponse>(bootResp.text)
                val selection = bootData.settlarSelection
                if (selection.isNullOrBlank()) continue

                // Step 2: Create Settlar session to get embedUrl with token
                val encSelection = java.net.URLEncoder.encode(selection, "UTF-8")
                val sessUrl = "$mainUrl/api/anime/settlar/session?selection=$encSelection&provider=anipm&ep=${payload.ep}&channel=$channel&telemetry=0"
                val sessResp = safeGet(
                    sessUrl,
                    mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to "$mainUrl/",
                        "Accept" to "application/json"
                    )
                ) ?: continue
                if (sessResp.code != 200) continue

                val sessData = mapper.readValue<AniPmSettlarSessionResponse>(sessResp.text)
                val embedUrl = sessData.embedUrl ?: continue

                val token = extractQueryParam(embedUrl, "t") ?: continue

                // Step 3: Fetch direct embed stream session from embed.settlar.io
                val encToken = java.net.URLEncoder.encode(token, "UTF-8")
                val streamApiUrl = "https://embed.settlar.io/api/embed/session?t=$encToken"
                val settlarApiHeaders = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "https://embed.settlar.io/",
                    "Origin" to "https://embed.settlar.io",
                    "Sec-Fetch-Dest" to "empty",
                    "Sec-Fetch-Mode" to "cors",
                    "Sec-Fetch-Site" to "same-origin",
                    "Accept" to "application/json"
                )
                val streamResp = try {
                    app.get(streamApiUrl, headers = settlarApiHeaders)
                } catch (_: Throwable) {
                    continue
                }

                if (streamResp.code != 200) continue

                val streamData = mapper.readValue<AniPmEmbedSessionResponse>(streamResp.text)
                val hlsSource = streamData.source

                if (!hlsSource.isNullOrBlank()) {
                    val channelLabel = if (channel == "dub") "English Dub" else "Japanese Sub"
                    val mediaHeaders = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to "https://embed.settlar.io/",
                        "Origin" to "https://embed.settlar.io",
                        "Sec-Fetch-Dest" to "empty",
                        "Sec-Fetch-Mode" to "cors",
                        "Sec-Fetch-Site" to "same-site",
                        "Accept" to "*/*"
                    )

                    // Master playlist link: provides adaptive streaming & robust native ExoPlayer playback
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "$name - $channelLabel [Auto]",
                            url = hlsSource,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "https://embed.settlar.io/"
                            this.headers = mediaHeaders
                            this.quality = Qualities.P1080.value
                        }
                    )
                    foundAny = true

                    // Also parse specific quality sub-streams (1080p, 720p, etc.)
                    try {
                        M3u8Helper.generateM3u8(
                            source = "$name - $channelLabel",
                            streamUrl = hlsSource,
                            referer = "https://embed.settlar.io/",
                            headers = mediaHeaders
                        ).forEach {
                            callback.invoke(it)
                        }
                    } catch (_: Throwable) {}

                    // Extract subtitles with required Settlar media headers
                    streamData.subtitles.orEmpty().forEach { sub ->
                        val subUrl = sub.url
                        if (!subUrl.isNullOrBlank()) {
                            val label = sub.label ?: sub.srclang ?: "English"
                            subtitleCallback.invoke(
                                newSubtitleFile(
                                    lang = label,
                                    url = subUrl
                                ) {
                                    this.headers = mediaHeaders
                                }
                            )
                        }

                        val assUrl = sub.assUrl
                        if (!assUrl.isNullOrBlank()) {
                            val label = "${sub.label ?: "English"} (ASS)"
                            subtitleCallback.invoke(
                                newSubtitleFile(
                                    lang = label,
                                    url = assUrl
                                ) {
                                    this.headers = mediaHeaders
                                }
                            )
                        }
                    }
                }
            } catch (_: Throwable) {
                // Try next channel
            }
        }

        return foundAny
    }
}

// Data models for Ani.pm API

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniPmHomeFeedResponse(
    @JsonProperty("feed") val feed: Map<String, AniPmFeedSection>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniPmFeedSection(
    @JsonProperty("items") val items: List<AniPmAnimeItem>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniPmSearchResponse(
    @JsonProperty("items") val items: List<AniPmAnimeItem>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniPmAnimeItem(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("source") val source: String? = null,
    @JsonProperty("slug") val slug: String? = null,
    @JsonProperty("routeId") val routeId: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("native") val native: String? = null,
    @JsonProperty("poster") val poster: String? = null,
    @JsonProperty("banner") val banner: String? = null,
    @JsonProperty("year") val year: Int? = null,
    @JsonProperty("score") val score: Double? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("episodeCount") val episodeCount: Int? = null,
    @JsonProperty("subCount") val subCount: Int? = null,
    @JsonProperty("dubCount") val dubCount: Int? = null,
    @JsonProperty("genres") val genres: List<String>? = null,
    @JsonProperty("synopsis") val synopsis: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniPmSeriesDetail(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("source") val source: String? = null,
    @JsonProperty("slug") val slug: String? = null,
    @JsonProperty("routeId") val routeId: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("native") val native: String? = null,
    @JsonProperty("romaji") val romaji: String? = null,
    @JsonProperty("poster") val poster: String? = null,
    @JsonProperty("banner") val banner: String? = null,
    @JsonProperty("heroBackdrop") val heroBackdrop: String? = null,
    @JsonProperty("year") val year: Int? = null,
    @JsonProperty("score") val score: Double? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("genres") val genres: List<String>? = null,
    @JsonProperty("synopsis") val synopsis: String? = null,
    @JsonProperty("episodes") val episodes: List<AniPmEpisodeItem>? = null,
    @JsonProperty("malId") val malId: Int? = null,
    @JsonProperty("anilistId") val anilistId: String? = null,
    @JsonProperty("trailer") val trailer: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniPmEpisodeItem(
    @JsonProperty("number") val number: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("thumbnail") val thumbnail: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("sub") val sub: Boolean? = null,
    @JsonProperty("dub") val dub: Boolean? = null,
    @JsonProperty("runtime") val runtime: Int? = null,
    @JsonProperty("routeId") val routeId: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniPmPlayPayload(
    val source: String,
    val id: Int,
    val ep: Int,
    val hasSub: Boolean = true,
    val hasDub: Boolean = false
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniPmBootstrapResponse(
    @JsonProperty("settlarSelection") val settlarSelection: String? = null,
    @JsonProperty("source") val source: String? = null,
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("episode") val episode: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniPmSettlarSessionResponse(
    @JsonProperty("embedUrl") val embedUrl: String? = null,
    @JsonProperty("expiresAt") val expiresAt: Long? = null,
    @JsonProperty("provider") val provider: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniPmEmbedSessionResponse(
    @JsonProperty("source") val source: String? = null,
    @JsonProperty("kind") val kind: String? = null,
    @JsonProperty("audioLang") val audioLang: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("subtitles") val subtitles: List<AniPmSubtitle>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniPmSubtitle(
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("srclang") val srclang: String? = null,
    @JsonProperty("assUrl") val assUrl: String? = null,
    @JsonProperty("default") val default: Boolean? = null
)
