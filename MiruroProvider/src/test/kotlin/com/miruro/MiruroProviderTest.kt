package com.miruro

import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.TvType
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class MiruroProviderTest {

    private lateinit var provider: MiruroProvider

    @Before
    fun setUp() {
        provider = MiruroProvider()
    }

    @Test
    fun testProviderProperties() {
        assertEquals("Miruro", provider.name)
        assertEquals("https://miruro.cx", provider.mainUrl)
        assertTrue(provider.hasMainPage)
        assertEquals("en", provider.lang)
        assertTrue(provider.supportedTypes.contains(TvType.Anime))
        assertTrue(provider.supportedTypes.contains(TvType.AnimeMovie))
        assertTrue(provider.supportedTypes.contains(TvType.OVA))
    }

    @Test
    fun testExtractAnimeId() {
        assertEquals("7oSZWutNHE2AGglkr7YdMEgEAPg9ZKjy", provider.extractAnimeId("https://miruro.cx/info/7oSZWutNHE2AGglkr7YdMEgEAPg9ZKjy/attack-on-titan"))
        assertEquals("7oSZWutNHE2AGglkr7YdMEgEAPg9ZKjy", provider.extractAnimeId("https://miruro.cx/watch/7oSZWutNHE2AGglkr7YdMEgEAPg9ZKjy?ep=1"))
        assertEquals("test-123", provider.extractAnimeId("/info/test-123"))
    }

    @Test
    fun testParseHtmlCards() {
        val sampleHtml = """
            <html>
            <body>
                <a href="/info/hDvb0oUMQRQgz9cJmhJULNQxNGU8ALkI/naruto" class="group">
                    <img src="https://s4.anilist.co/poster.jpg" alt="Naruto"/>
                </a>
                <article data-cover-card="8rVxkkxRnGt12LItY2fnNEh1k1E7mmbA">
                    <a href="/info/8rVxkkxRnGt12LItY2fnNEh1k1E7mmbA/the-apothecary-diaries" aria-label="The Apothecary Diaries">
                        <img src="https://image.tmdb.org/backdrop.jpg"/>
                    </a>
                </article>
            </body>
            </html>
        """.trimIndent()
        val doc = Jsoup.parse(sampleHtml, "https://miruro.cx")
        val cards = provider.parseHtmlCards(doc)
        assertEquals(2, cards.size)
        assertEquals("Naruto", cards[0].name)
        assertEquals("https://miruro.cx/info/hDvb0oUMQRQgz9cJmhJULNQxNGU8ALkI/naruto", cards[0].url)
        assertEquals("The Apothecary Diaries", cards[1].name)
    }

    @Test
    fun testTypeDetection() {
        assertEquals(TvType.AnimeMovie, MiruroProvider.getType("Movie"))
        assertEquals(TvType.AnimeMovie, MiruroProvider.getType("ANIME_MOVIE"))
        assertEquals(TvType.OVA, MiruroProvider.getType("OVA"))
        assertEquals(TvType.OVA, MiruroProvider.getType("SPECIAL"))
        assertEquals(TvType.Anime, MiruroProvider.getType("TV"))
        assertEquals(TvType.Anime, MiruroProvider.getType(null))
    }

    @Test
    fun testStatusDetection() {
        assertEquals(ShowStatus.Completed, MiruroProvider.getStatus("FINISHED"))
        assertEquals(ShowStatus.Completed, MiruroProvider.getStatus("completed"))
        assertEquals(ShowStatus.Ongoing, MiruroProvider.getStatus("RELEASING"))
        assertEquals(ShowStatus.Ongoing, MiruroProvider.getStatus("NOT_YET_RELEASED"))
        assertEquals(ShowStatus.Ongoing, MiruroProvider.getStatus(null))
    }

    @Test
    fun testBase64UrlEncode() {
        val testString = "https://example.com/stream/index.m3u8"
        val encoded = MiruroProvider.base64UrlEncode(testString.toByteArray(Charsets.UTF_8))
        assertFalse(encoded.contains("+"))
        assertFalse(encoded.contains("/"))
        assertFalse(encoded.contains("="))
        assertTrue(encoded.isNotEmpty())
    }

    @Test
    fun testBase64Decode() {
        val original = "Hello Miruro & Ani.pm Cloudstream!"
        val encoded = MiruroProvider.base64UrlEncode(original.toByteArray(Charsets.UTF_8))
        val decoded = MiruroProvider.base64Decode(encoded)
        assertEquals(original, String(decoded, Charsets.UTF_8))

        // Test standard padded base64 string
        val standardB64 = "SGVsbG8gV29ybGQ="
        val decodedStd = MiruroProvider.base64Decode(standardB64)
        assertEquals("Hello World", String(decodedStd, Charsets.UTF_8))
    }

    @Test
    fun testKeeplyProxyUrlGeneration() {
        val targetStream = "https://example.com/playlist.m3u8"
        val referer = "https://www.miruro.cx/"
        val proxyUrl = MiruroProvider.buildKeeplyProxyUrl(targetStream, referer)

        assertTrue(proxyUrl.startsWith("https://s1.keeply.top/"))
        assertTrue(proxyUrl.endsWith("/pl.m3u8"))
        assertTrue(proxyUrl.contains("~"))

        // Test subtitle route
        val subUrl = MiruroProvider.buildKeeplyProxyUrl(targetStream, referer, "sub.vtt")
        assertTrue(subUrl.endsWith("/sub.vtt"))
    }

    @Test
    fun testCatalogDecryption() {
        val sampleJson = """{"data":[{"id":"test-123","title":{"english":"Frieren"}}]}"""
        // Compress with GZIP
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use {
            it.write(sampleJson.toByteArray(Charsets.UTF_8))
        }
        val gzipped = baos.toByteArray()

        // Encrypt with XOR
        val key = MiruroProvider.CATALOG_KEY.toByteArray(Charsets.UTF_8)
        val encrypted = ByteArray(gzipped.size)
        for (i in gzipped.indices) {
            encrypted[i] = (gzipped[i].toInt() xor key[i % key.size].toInt()).toByte()
        }

        // Test decryption
        val decrypted = MiruroProvider.decryptCatalog(encrypted)
        assertEquals(sampleJson, decrypted)
    }

    @Test
    fun testSsrDataDecryptionAndParsing() {
        val realJson = """
            {
              "id": "7oSZWutNHE2AGglkr7YdMEgEAPg9ZKjy",
              "title": {
                "romaji": "Shingeki no Kyojin",
                "english": "Attack on Titan",
                "native": "進撃の巨人"
              },
              "alternate_titles": [{"value": "Advancing Giants", "kind": "SYNONYM"}],
              "format": "TV",
              "status": "FINISHED",
              "season": "SPRING",
              "season_year": 2013,
              "episode_count": 25,
              "cover_url": "https://image.tmdb.org/poster.jpg",
              "banner_url": "https://image.tmdb.org/banner.jpg",
              "description": "Several hundred years ago, humans were nearly exterminated by titans.",
              "trailer_youtube_id": "LHtdKWJdif4",
              "genres": ["Action", "Drama", "Fantasy"],
              "external_ids": {
                "mal": ["16498"],
                "anilist": ["16498"]
              }
            }
        """.trimIndent()

        // Compress and encrypt
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use {
            it.write(realJson.toByteArray(Charsets.UTF_8))
        }
        val gzipped = baos.toByteArray()
        val key = MiruroProvider.CATALOG_KEY.toByteArray(Charsets.UTF_8)
        val encrypted = ByteArray(gzipped.size)
        for (i in gzipped.indices) {
            encrypted[i] = (gzipped[i].toInt() xor key[i % key.size].toInt()).toByte()
        }

        val b64Encoded = MiruroProvider.base64UrlEncode(encrypted)
        val decodedBytes = MiruroProvider.base64Decode(b64Encoded)
        val decryptedJson = MiruroProvider.decryptCatalog(decodedBytes)
        assertEquals(realJson, decryptedJson)

        val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().apply {
            configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        }
        val detail = mapper.readValue(decryptedJson, MiruroAnimeDetail::class.java)
        assertEquals("7oSZWutNHE2AGglkr7YdMEgEAPg9ZKjy", detail.id)
        assertEquals("Attack on Titan", detail.title?.english)
        assertEquals(25, detail.episodeCount)
        assertEquals("LHtdKWJdif4", detail.trailerYoutubeId)
        assertEquals("16498", detail.externalIds?.get("mal")?.firstOrNull())
        assertEquals("16498", detail.externalIds?.get("anilist")?.firstOrNull())
    }

    @Test
    fun testLdJsonParsing() {
        val sampleHtml = """
            <html>
            <head>
                <script type="application/ld+json">
                {
                    "@context": "https://schema.org",
                    "@type": "ItemList",
                    "name": "Trending Anime",
                    "itemListElement": [
                        {
                            "@type": "ListItem",
                            "position": 1,
                            "name": "One Piece",
                            "url": "https://www.miruro.cx/info/o8FU9Se7l1zWPEIo28kel7S1tthb6ldF/one-piece"
                        },
                        {
                            "@type": "ListItem",
                            "position": 2,
                            "name": "Naruto Shippuden",
                            "url": "https://www.miruro.cx/info/DUGBASzhpiMhAKoYon5sQMtd8DkPdk3-/naruto-shippuden"
                        }
                    ]
                }
                </script>
            </head>
            <body></body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(sampleHtml, "https://www.miruro.cx")
        val scripts = doc.select("script[type='application/ld+json']")
        assertEquals(1, scripts.size)
        assertTrue(scripts[0].data().contains("One Piece"))
    }

    @Test
    fun testSvelteKitEpisodeExtraction() {
        val sampleHtml = """
            <script>
            a.id="1";a.number=1;a.title="Locusts";a.image="https://image.tmdb.org/poster1.jpg";
            b.id="2";b.number=2;b.title="The Fire-Rat Cloak";b.image="https://image.tmdb.org/poster2.jpg";
            return [{episodes:[a,b,{id:"3",number:3,title:"Let Them Eat Cake",image:"https://image.tmdb.org/poster3.jpg",airDate:"2026-10-16"}]}]
            </script>
        """.trimIndent()

        val inlinedRegex = Regex("""\{id:"([^"]*)",number:(\d+),title:"([^"]*)",(?:image:(?:"([^"]*)"|null|void 0)|[^}]*)""")
        val assignRegex = Regex("""\b([a-zA-Z0-9_$]+)\.id="([^"]*)";\1\.number=(\d+);\1\.title="([^"]*)";(?:\1\.image="([^"]*)")?""")

        val episodes = mutableMapOf<Int, String>()
        assignRegex.findAll(sampleHtml).forEach {
            episodes[it.groupValues[3].toInt()] = it.groupValues[4]
        }
        inlinedRegex.findAll(sampleHtml).forEach {
            episodes[it.groupValues[2].toInt()] = it.groupValues[3]
        }

        assertEquals(3, episodes.size)
        assertEquals("Locusts", episodes[1])
        assertEquals("The Fire-Rat Cloak", episodes[2])
        assertEquals("Let Them Eat Cake", episodes[3])
    }

    @Test
    fun testMiruroPlayPayloadWithWatchUrl() {
        val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
        val payload = MiruroPlayPayload(
            animeId = "test-123",
            episodeId = "1",
            watchUrl = "https://miruro.cx/watch/test-123/my-anime?ep=1"
        )
        val json = mapper.writeValueAsString(payload)
        val deserialized = mapper.readValue(json, MiruroPlayPayload::class.java)
        assertEquals("test-123", deserialized.animeId)
        assertEquals("1", deserialized.episodeId)
        assertEquals("https://miruro.cx/watch/test-123/my-anime?ep=1", deserialized.watchUrl)
    }

    @Test
    fun testSubtitleFileHeaders() = kotlinx.coroutines.runBlocking {
        val sub = com.lagradost.cloudstream3.newSubtitleFile(
            lang = "English",
            url = "https://mysubsplay.com/sub.vtt"
        ) {
            this.headers = mapOf("Origin" to "https://megaplay.buzz")
        }
        assertEquals("https://megaplay.buzz", sub.headers?.get("Origin"))
    }
}
