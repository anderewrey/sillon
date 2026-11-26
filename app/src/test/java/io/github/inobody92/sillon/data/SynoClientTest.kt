package io.github.inobody92.sillon.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SynoClientTest {

    private lateinit var server: MockWebServer
    private lateinit var dsm: FakeDsm

    @Before fun setUp() {
        dsm = FakeDsm()
        server = MockWebServer().apply { dispatcher = dsm; start() }
    }

    @After fun tearDown() = server.shutdown()

    private fun client(password: String = "secret", deviceId: String? = null) =
        SynoClient(Credentials(server.url("/").toString().trimEnd('/'), "maxime", password, false, deviceId))

    @Test fun adresseNormalisee() {
        assertEquals("https://nas.exemple.fr:5001", SynoClient.normalizeUrl(" nas.exemple.fr:5001/ "))
        assertEquals("https://nas", SynoClient.normalizeUrl("HTTPS://nas"))
        assertThrows(IllegalArgumentException::class.java) { SynoClient.normalizeUrl("http://nas") }
        assertThrows(IllegalArgumentException::class.java) { SynoClient.normalizeUrl("   ") }
    }

    @Test fun connexionReussie() = runBlocking {
        val c = client()
        c.connect()
        assertEquals("SID1", c.sid)
        assertEquals("DEVICE", c.deviceId)
    }

    @Test fun lesCheminsViennentDeLaDecouverteEtPasDuCode() = runBlocking {
        val c = client()
        c.connect()
        val albums = c.albums(0, 10)
        assertEquals("13", albums.single().name)
        assertEquals("Indochine", albums.single().displayArtist)
    }

    @Test fun mauvaisMotDePasse() {
        val e = assertThrows(ApiException::class.java) { runBlocking { client(password = "faux").connect() } }
        assertEquals(400, e.code)
    }

    @Test fun doubleAuthentificationDemandee() {
        dsm.otp = "123456"
        assertThrows(OtpRequiredException::class.java) { runBlocking { client().connect() } }
    }

    @Test fun codeOtpAccepteEtAppareilDeConfianceMemorise() = runBlocking {
        dsm.otp = "123456"
        val c = client()
        c.connect(otp = "123456")
        assertEquals("DEVICE", c.deviceId)
        // Avec l'identifiant d'appareil, plus besoin de redemander le code.
        client(deviceId = "DEVICE").connect()
    }

    @Test fun lAppareilDeConfianceEstEnvoyeAvecSonNom() = runBlocking {
        dsm.otp = "123456"
        client(deviceId = "DEVICE").connect()
        val login = dsm.requests.last { it["method"] == "login" }
        assertEquals("DEVICE", login["device_id"])
        assertEquals("Sillon", login["device_name"])
        assertEquals("yes", login["enable_device_token"])
        assertEquals(null, login["otp_code"])
    }

    @Test fun lIdentifiantDAppareilRemisParLeNasEstSignalePourEtreEnregistre() = runBlocking {
        dsm.otp = "123456"
        val saved = mutableListOf<String>()
        val c = SynoClient(Credentials(server.url("/").toString().trimEnd('/'), "maxime", "secret", false)) { saved += it }
        c.connect(otp = "123456")
        assertEquals(listOf("DEVICE"), saved)
        // Reconnexion (session expirée) : même identifiant, rien de nouveau à enregistrer.
        dsm.expireSessionTimes = 1
        c.albums(0, 10)
        assertEquals(listOf("DEVICE"), saved)
    }

    @Test fun sessionExpireeAvecDoubleAuthentificationPasDeNouveauCode() = runBlocking {
        dsm.otp = "123456"
        val c = client()
        c.connect(otp = "123456")
        dsm.expireSessionTimes = 1
        // La reconnexion automatique passe par l'appareil de confiance : aucun code OTP n'est redemandé.
        assertEquals(1, c.albums(0, 10).size)
    }

    @Test fun uneReponseQuiNEstPasDuJsonEstUneErreurReseau() {
        server.shutdown()
        server = MockWebServer().apply {
            dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                    okhttp3.mockwebserver.MockResponse().setBody("<html>Portail captif</html>")
            }
            start()
        }
        assertThrows(java.io.IOException::class.java) { runBlocking { client().connect() } }
    }

    @Test fun sessionExpireeLeClientSeReconnecteEtReessaieUneFois() = runBlocking {
        val c = client()
        c.connect()
        dsm.expireSessionTimes = 1
        val albums = c.albums(0, 10)
        assertEquals(1, albums.size)
        assertEquals("SID2", c.sid) // une nouvelle session a été ouverte
    }

    @Test fun uneErreurQuiNEstPasDeSessionNEstPasRejouee() {
        val c = client()
        runBlocking { c.connect() }
        val e = assertThrows(ApiException::class.java) { runBlocking { c.call("SYNO.AudioStation.Song", "inconnue") } }
        assertEquals(103, e.code)
    }

    @Test fun conversionDemandeeMaisNonAnnonceeLitLOriginal() = runBlocking {
        dsm.transcode = emptyList()
        val c = client()
        c.connect()
        val url = c.streamUrl("music_1", "m4a", AudioQuality.LOW)!!
        assertTrue(url.contains("method=stream"))
        assertFalse(url.contains("transcode"))
    }

    @Test fun conversionMp3QuandLeNasLAnnonce() = runBlocking {
        dsm.transcode = listOf("mp3", "wav")
        val c = client()
        c.connect()
        assertTrue(c.canTranscodeMp3)
        val url = c.streamUrl("music_1", "m4a", AudioQuality.LOW)!!
        assertTrue(url.contains("method=transcode"))
        assertTrue(url.contains("bitrate=128000"))
        assertTrue(url.contains("format=mp3"))
    }

    @Test fun cleDeCacheIgnoreLeSid() = runBlocking {
        val c = client()
        c.connect()
        val withSid = c.songCoverUrl("music_1", withSid = true)!!
        assertTrue(withSid.contains("_sid=SID1"))
        assertFalse(SynoClient.stableKey(withSid).contains("_sid"))
        assertEquals("file:///tmp/a.jpg", SynoClient.stableKey("file:///tmp/a.jpg")) // adresse locale : inchangée
    }

    @Test fun identifiantsSeparesParDesVirgulesSontEchappes() = runBlocking {
        val c = client()
        c.connect()
        val id = c.createPlaylist("Test", listOf("a,b", "c"))
        // La virgule d'un identifiant est échappée : le NAS reçoit bien deux titres, pas trois.
        assertEquals(2, dsm.playlists[id]!!.second.size)
    }

    @Test fun uneBibliothequeSansSessionRefuseLesAppels() {
        val c = client()
        assertThrows(ApiException::class.java) { runBlocking { c.call("SYNO.AudioStation.Album", "list") } }
    }
}
