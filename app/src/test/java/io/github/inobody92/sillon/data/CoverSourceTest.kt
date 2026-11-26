package io.github.inobody92.sillon.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CoverSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var dsm: FakeDsm
    private lateinit var dir: File
    private lateinit var cacheDir: File
    private lateinit var offlineDir: File

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3)

    @Before fun setUp() {
        dsm = FakeDsm()
        server = MockWebServer().apply { dispatcher = dsm; start() }
        dir = Files.createTempDirectory("cover-test").toFile()
        cacheDir = File(dir, "cache")
        offlineDir = File(dir, "offline").apply { mkdirs() }
    }

    @After fun tearDown() {
        server.shutdown()
        dir.deleteRecursively()
    }

    private fun connectedClient(): SynoClient = runBlocking {
        SynoClient(Credentials(server.url("/").toString().trimEnd('/'), "maxime", "secret", false)).also { it.connect() }
    }

    /** Session de test : [client] est la session ouverte ; [restored] ce que rendrait une réouverture. */
    private inner class TestAccess(var client: SynoClient?, var restored: () -> SynoClient? = { null }) : SessionAccess {
        var restores = 0
        override fun current() = client
        override fun restore(): SynoClient? { restores++; return restored().also { client = it } }
        override fun renew(client: SynoClient) = runBlocking { client.refreshSession() }
        override fun owner() = "nas|maxime"
    }

    private var now = 1_000_000L

    private fun source(access: SessionAccess, maxBytes: Long = 5L * 1024 * 1024) = CoverSource(
        offlineCover = { key -> File(offlineDir, "$key.jpg").takeIf { it.isFile } },
        cacheDir = cacheDir,
        gate = SessionGate(access, clock = { now }),
        maxBytes = maxBytes,
        clock = { now },
    )

    private fun cachedImages() = cacheDir.listFiles()?.filter { it.name.endsWith(".img") }.orEmpty()

    @Test fun laPochetteHorsLigneSuffitSansReseau() {
        File(offlineDir, "abc123.jpg").writeBytes(jpeg)
        val file = source(TestAccess(null)).open(CoverRequest.Song("music_1", "abc123"))
        assertArrayEquals(jpeg, file!!.readBytes())
        assertEquals(0, dsm.coverRequests)
    }

    @Test fun laPochetteVientDuNasAvecLeSidPuisDuCache() {
        val client = connectedClient()
        dsm.covers["song:music_1"] = jpeg
        val src = source(TestAccess(client))
        val first = src.open(CoverRequest.Song("music_1", null))
        assertArrayEquals(jpeg, first!!.readBytes())
        assertEquals("SID1", server.takeRequestWithPath("cover.cgi")?.requestUrl?.queryParameter("_sid"))
        val second = src.open(CoverRequest.Song("music_1", null))
        assertArrayEquals(jpeg, second!!.readBytes())
        assertEquals(1, dsm.coverRequests)
    }

    @Test fun pochetteDAlbumParNomEtArtiste() {
        val client = connectedClient()
        dsm.covers["album:13|Indochine"] = jpeg
        val file = source(TestAccess(client)).open(CoverRequest.Album("13", "Indochine", null))
        assertArrayEquals(jpeg, file!!.readBytes())
    }

    @Test fun uneErreurJsonNEstJamaisMiseEnCache() {
        val client = connectedClient()
        assertNull(source(TestAccess(client)).open(CoverRequest.Song("music_404", null)))
        assertTrue(cachedImages().isEmpty())
    }

    @Test fun uneImageTropGrosseEstRefusee() {
        val client = connectedClient()
        dsm.covers["song:music_1"] = ByteArray(2048) { 1 }
        assertNull(source(TestAccess(client), maxBytes = 1024).open(CoverRequest.Song("music_1", null)))
        assertTrue(cacheDir.listFiles().orEmpty().isEmpty())
    }

    @Test fun sessionExpireeUneSeuleReconnexionPourVingtPochettes() {
        val client = connectedClient()
        (1..20).forEach { dsm.covers["song:music_$it"] = jpeg }
        dsm.revokedSid = "SID1"
        val loginsBefore = dsm.logins
        val src = source(TestAccess(client))
        val pool = Executors.newFixedThreadPool(20)
        val results = (1..20).map { i -> pool.submit<File?> { src.open(CoverRequest.Song("music_$i", null)) } }
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
        assertTrue(results.all { it.get() != null })
        assertEquals(loginsBefore + 1, dsm.logins)
        assertEquals("SID2", client.sid)
    }

    @Test fun apresUnEchecDeReconnexionOnAttendAvantDeReessayer() {
        val client = connectedClient()
        dsm.covers["song:music_1"] = jpeg
        dsm.covers["song:music_2"] = jpeg
        dsm.revokedSid = "SID1"
        dsm.password = "changé"
        val loginsBefore = dsm.logins
        val src = source(TestAccess(client))
        assertNull(src.open(CoverRequest.Song("music_1", null)))
        assertNull(src.open(CoverRequest.Song("music_2", null)))
        assertEquals(loginsBefore + 1, dsm.logins)
        // Après la pause, une nouvelle tentative est permise.
        dsm.password = "secret"
        now += 61_000
        assertNotNull(src.open(CoverRequest.Song("music_2", null)))
        assertEquals(loginsBefore + 2, dsm.logins)
    }

    @Test fun demarrageAFroidRouvreLaSessionUneSeuleFoisEnCasDEchec() {
        val access = TestAccess(null) { null }
        val src = source(access)
        assertNull(src.open(CoverRequest.Song("music_1", null)))
        assertNull(src.open(CoverRequest.Song("music_2", null)))
        assertEquals(1, access.restores)
    }

    @Test fun demarrageAFroidAvecSessionRetrouvee() {
        dsm.covers["song:music_1"] = jpeg
        val access = TestAccess(null) { connectedClient() }
        assertNotNull(source(access).open(CoverRequest.Song("music_1", null)))
        assertEquals(1, access.restores)
    }

    @Test fun nasInjoignableOnNeRetentePasTout() {
        val client = connectedClient()
        server.shutdown() // NAS hors d'atteinte (hors du réseau local)
        var lookups = 0
        val access = object : SessionAccess {
            override fun current(): SynoClient { lookups++; return client }
            override fun restore(): SynoClient? = null
            override fun renew(client: SynoClient) = Unit
            override fun owner() = "nas|maxime"
        }
        val src = source(access)
        assertNull(src.open(CoverRequest.Song("music_1", null)))
        assertEquals(1, lookups)
        // Pendant 30 s, les autres pochettes échouent tout de suite, sans tentative réseau.
        assertNull(src.open(CoverRequest.Song("music_2", null)))
        assertEquals(1, lookups)
        now += 31_000
        assertNull(src.open(CoverRequest.Song("music_3", null)))
        assertEquals(2, lookups)
    }

    @Test fun lesDemandesHostilesSontRefusees() {
        val params = mapOf("id" to "../../x", "k" to "../../../etc/passwd")
        assertNull(CoverRequest.parse("song", params::get))
        assertNull(CoverRequest.parse("autre", mapOf("id" to "music_1")::get))
        assertNull(CoverRequest.parse("album", mapOf("artist" to "Indochine")::get))
        assertNull(CoverRequest.parse("album", mapOf("name" to "x".repeat(600))::get))
        // La clé hors ligne n'est validée qu'à l'usage, par OfflineStore.coverFileForKey.
        assertEquals(CoverRequest.Song("music_1", "../x"), CoverRequest.parse("song", mapOf("id" to "music_1", "k" to "../x")::get))
        assertEquals(
            CoverRequest.Album("13", "Indochine", "1a2b"),
            CoverRequest.parse("album", mapOf("name" to "13", "artist" to "Indochine", "k" to "1a2b")::get),
        )
    }

    private fun MockWebServer.takeRequestWithPath(part: String) =
        generateSequence { takeRequest(1, TimeUnit.SECONDS) }.firstOrNull { it.path.orEmpty().contains(part) }
}
