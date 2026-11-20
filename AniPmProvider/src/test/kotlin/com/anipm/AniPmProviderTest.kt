package com.anipm

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.TvType
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AniPmProviderTest {

    private lateinit var provider: AniPmProvider
    private val mapper = jacksonObjectMapper()

    @Before
    fun setUp() {
        provider = AniPmProvider()
    }

    @Test
    fun testProviderProperties() {
        assertEquals("Ani.pm", provider.name)
        assertEquals("https://ani.pm", provider.mainUrl)
        assertTrue(provider.hasMainPage)
        assertEquals("en", provider.lang)
        assertTrue(provider.supportedTypes.contains(TvType.Anime))
        assertTrue(provider.supportedTypes.contains(TvType.AnimeMovie))
        assertTrue(provider.supportedTypes.contains(TvType.OVA))
    }

    @Test
    fun testTypeDetection() {
        assertEquals(TvType.AnimeMovie, AniPmProvider.getType("Movie"))
        assertEquals(TvType.AnimeMovie, AniPmProvider.getType("MOVIE"))
        assertEquals(TvType.OVA, AniPmProvider.getType("OVA"))
        assertEquals(TvType.OVA, AniPmProvider.getType("Special"))
        assertEquals(TvType.Anime, AniPmProvider.getType("TV"))
        assertEquals(TvType.Anime, AniPmProvider.getType("ONA"))
        assertEquals(TvType.Anime, AniPmProvider.getType(null))
    }

    @Test
    fun testStatusDetection() {
        assertEquals(ShowStatus.Completed, AniPmProvider.getStatus("Finished"))
        assertEquals(ShowStatus.Completed, AniPmProvider.getStatus("Completed"))
        assertEquals(ShowStatus.Ongoing, AniPmProvider.getStatus("Currently Airing"))
        assertEquals(ShowStatus.Ongoing, AniPmProvider.getStatus("Releasing"))
        assertEquals(ShowStatus.Ongoing, AniPmProvider.getStatus(null))
    }

    @Test
    fun testFixAniPmUrl() {
        assertEquals(
            "https://ani.pm/api/anime/cover?key=abc",
            AniPmProvider.fixAniPmUrl("https://ani.pm", "/api/anime/cover?key=abc")
        )
        assertEquals(
            "https://ani.pm/api/anime/cover?key=abc",
            AniPmProvider.fixAniPmUrl("https://ani.pm", "api/anime/cover?key=abc")
        )
        assertEquals(
            "https://media.settlar.io/image.png",
            AniPmProvider.fixAniPmUrl("https://ani.pm", "https://media.settlar.io/image.png")
        )
        assertNull(AniPmProvider.fixAniPmUrl("https://ani.pm", null))
        assertNull(AniPmProvider.fixAniPmUrl("https://ani.pm", ""))
    }

    @Test
    fun testExtractQueryParam() {
        val testUrl = "https://embed.settlar.io/embed/v1?t=2.token123abc&auto=1"
        assertEquals("2.token123abc", AniPmProvider.extractQueryParam(testUrl, "t"))
        assertEquals("1", AniPmProvider.extractQueryParam(testUrl, "auto"))
        assertNull(AniPmProvider.extractQueryParam(testUrl, "missing"))

        val animeUrl = "https://ani.pm/anime/one-piece-1642?source=settlar&id=1642"
        assertEquals("settlar", AniPmProvider.extractQueryParam(animeUrl, "source"))
        assertEquals("1642", AniPmProvider.extractQueryParam(animeUrl, "id"))
    }

    @Test
    fun testAnimeIdExtraction() {
        fun extractId(url: String): String? {
            return AniPmProvider.extractQueryParam(url, "id")
                ?: Regex("""(?:-(\d+)(?:-[a-zA-Z0-9]+)?|/anime/(\d+))(?:[?#]|$)""").find(url)?.let {
                    it.groupValues.getOrNull(1)?.takeIf { v -> v.isNotEmpty() }
                        ?: it.groupValues.getOrNull(2)?.takeIf { v -> v.isNotEmpty() }
                }
        }

        assertEquals("1642", extractId("https://ani.pm/anime/one-piece-1642-luixja"))
        assertEquals("1642", extractId("https://ani.pm/anime/one-piece-1642"))
        assertEquals("1642", extractId("https://ani.pm/anime/1642"))
        assertEquals("1642", extractId("https://ani.pm/anime/1642?source=settlar"))
        assertEquals("1642", extractId("https://ani.pm/anime/one-piece?source=settlar&id=1642"))
    }

    @Test
    fun testExtractorLinkHeaders() = kotlinx.coroutines.runBlocking {
        val link = com.lagradost.cloudstream3.utils.newExtractorLink(
            "test",
            "test",
            "https://example.com",
            com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8
        ) {
            this.referer = "https://example.com"
            this.headers = mapOf("Origin" to "https://example.com")
        }
        assertEquals("https://example.com", link.headers["Origin"])
    }

    @Test
    fun testSearchResponseParsing() {
        val json = """
            {
              "items": [
                {
                  "id": 1642,
                  "source": "settlar",
                  "slug": "one-piece-odmau",
                  "title": "One Piece",
                  "native": "One Piece",
                  "poster": "/api/anime/cover?key=abc",
                  "year": 1999,
                  "score": 8.72,
                  "type": "TV",
                  "episodeCount": 1180,
                  "subCount": 1180,
                  "dubCount": 1157,
                  "routeId": "one-piece-1642-luixja"
                }
              ]
            }
        """.trimIndent()

        val parsed = mapper.readValue<AniPmSearchResponse>(json)
        assertNotNull(parsed.items)
        assertEquals(1, parsed.items?.size)

        val item = parsed.items?.get(0)
        assertEquals(1642, item?.id)
        assertEquals("One Piece", item?.title)
        assertEquals("settlar", item?.source)
        assertEquals(1180, item?.episodeCount)
        assertEquals(1157, item?.dubCount)
    }

    @Test
    fun testSeriesDetailParsing() {
        val json = """
            {
              "id": 1642,
              "source": "settlar",
              "slug": "one-piece-odmau",
              "title": "One Piece",
              "native": "One Piece",
              "poster": "/api/anime/cover?key=abc",
              "banner": null,
              "year": 1999,
              "score": 8.72,
              "type": "TV",
              "status": "Currently Airing",
              "genres": ["Action", "Adventure"],
              "synopsis": "Greatest treasure...",
              "malId": 21,
              "anilistId": "21",
              "episodes": [
                {
                  "number": 1,
                  "title": "I'm Luffy!",
                  "thumbnail": "/api/anime/art/still/21/1",
                  "description": "Alvida pirates...",
                  "sub": true,
                  "dub": true,
                  "runtime": 25,
                  "routeId": "e1.abc"
                }
              ]
            }
        """.trimIndent()

        val detail = mapper.readValue<AniPmSeriesDetail>(json)
        assertEquals(1642, detail.id)
        assertEquals("One Piece", detail.title)
        assertEquals(21, detail.malId)
        assertEquals("21", detail.anilistId)
        assertEquals(1, detail.episodes?.size)

        val ep = detail.episodes?.get(0)
        assertEquals(1, ep?.number)
        assertEquals("I'm Luffy!", ep?.title)
        assertTrue(ep?.sub == true)
        assertTrue(ep?.dub == true)
    }

    @Test
    fun testPlaybackStreamResponses() {
        val bootJson = """
            {
              "settlarSelection": "2.A1kjzJYO7zPAhmBpgwiCeb3bpmssNm4Z",
              "source": "settlar",
              "id": 1642,
              "episode": 1
            }
        """.trimIndent()
        val boot = mapper.readValue<AniPmBootstrapResponse>(bootJson)
        assertEquals("2.A1kjzJYO7zPAhmBpgwiCeb3bpmssNm4Z", boot.settlarSelection)

        val sessJson = """
            {
              "embedUrl": "https://embed.settlar.io/embed/v1?t=2.tokenXYZ123",
              "expiresAt": 1795212076,
              "provider": "anipm"
            }
        """.trimIndent()
        val sess = mapper.readValue<AniPmSettlarSessionResponse>(sessJson)
        assertEquals("https://embed.settlar.io/embed/v1?t=2.tokenXYZ123", sess.embedUrl)
        assertEquals("anipm", sess.provider)

        val embedJson = """
            {
              "sessionId": "viewer",
              "source": "https://media.settlar.io/v1/object/v2.stream.m3u8",
              "kind": "hls",
              "audioLang": "ja",
              "subtitles": [
                {
                  "url": "https://media.settlar.io/sub.vtt",
                  "label": "English",
                  "srclang": "en",
                  "assUrl": "https://media.settlar.io/sub.ass",
                  "default": true
                }
              ]
            }
        """.trimIndent()
        val stream = mapper.readValue<AniPmEmbedSessionResponse>(embedJson)
        assertEquals("https://media.settlar.io/v1/object/v2.stream.m3u8", stream.source)
        assertEquals("hls", stream.kind)
        assertEquals("ja", stream.audioLang)
        assertEquals(1, stream.subtitles?.size)
        assertEquals("English", stream.subtitles?.get(0)?.label)
        assertEquals("https://media.settlar.io/sub.ass", stream.subtitles?.get(0)?.assUrl)
    }

    @Test
    fun testPlayPayloadSerialization() {
        val payload = AniPmPlayPayload(
            source = "settlar",
            id = 1642,
            ep = 5,
            hasSub = true,
            hasDub = true
        )
        val json = mapper.writeValueAsString(payload)
        val deserialized = mapper.readValue<AniPmPlayPayload>(json)
        assertEquals("settlar", deserialized.source)
        assertEquals(1642, deserialized.id)
        assertEquals(5, deserialized.ep)
        assertTrue(deserialized.hasSub)
        assertTrue(deserialized.hasDub)
    }
}
