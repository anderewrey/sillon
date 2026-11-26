package io.github.inobody92.sillon.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTest {

    @Test fun titreAllerRetourJson() {
        val s = Song("music_1", "Titre", "Artiste", "Album", "Artiste d'album", 4, 2, 210, "m4a", "Rock", 2017)
        assertEquals(s, songFromJson(s.toJson()))
        assertEquals(listOf(s), songsFromJson(listOf(s).toJsonArray()))
    }

    @Test fun extensionVenueDuNasAssainie() {
        assertEquals("flac", safeExtension("FLAC"))
        assertEquals("m4a", safeExtension("m4a"))
        assertEquals("", safeExtension("mp3/../../index.json"))
        assertEquals("", safeExtension("toolongext"))
        assertEquals("", safeExtension(""))
    }

    @Test fun anciennesDonneesSansGenreNiAnneeRestentLisibles() {
        val o = org.json.JSONObject().put("id", "x").put("title", "t").put("ext", "mp3")
        val s = songFromJson(o)
        assertEquals("", s.genre)
        assertEquals(0, s.year)
    }

    @Test fun humeursChoisissentLesGenresDeLaBibliotheque() {
        val library = listOf("Rock", "Metal", "Jazz", "Chanson française", "Podcast")
        assertTrue("Rock" in Mood.ENERGY.defaultGenres(library))
        assertTrue("Jazz" in Mood.CHILL.defaultGenres(library))
        assertTrue("Chanson française" in Mood.NOSTALGIA.defaultGenres(library))
        assertFalse("Podcast" in Mood.ENERGY.defaultGenres(library))
    }

    @Test fun lienQuickConnectDistingueUnIdDUneAdresse() {
        assertTrue(QuickConnect.looksLikeId("mon-nas"))
        assertTrue(QuickConnect.looksLikeId("  invendi "))
        assertFalse(QuickConnect.looksLikeId("monnas.synology.me"))
        assertFalse(QuickConnect.looksLikeId("192.168.1.10"))
        assertFalse(QuickConnect.looksLikeId("monnas:5001"))
        assertFalse(QuickConnect.looksLikeId("https://monnas"))
        assertFalse(QuickConnect.looksLikeId("5001"))
        assertFalse(QuickConnect.looksLikeId("ab"))
    }

    @Test fun quickConnectPrefereLesAdressesDirectesAuRelais() {
        val info = org.json.JSONObject(
            """{"server":{"ddns":"mon.synology.me","fqdn":"NULL","external":{"ip":"1.2.3.4"},
               "interface":[{"ip":"192.168.1.10"}]},
               "service":{"port":5000,"ext_port":5001},
               "smartdns":{"host":"mon-nas.direct.quickconnect.to","lan":["192.168.1.10"]}}""",
        )
        val list = QuickConnect.candidates(info, "mon-nas")
        assertEquals("https://mon-nas.direct.quickconnect.to:5001", list.first())
        assertEquals("https://mon-nas.quickconnect.to", list.last()) // relais en dernier recours
        assertTrue("https://mon.synology.me:5001" in list)
        assertEquals(list.size, list.distinct().size)
        assertTrue(list.none { it.contains("NULL") })
    }

    @Test fun quickConnectSansInfoRetombeSurLeRelais() {
        assertEquals(listOf("https://mon-nas.quickconnect.to"), QuickConnect.candidates(org.json.JSONObject("{}"), "mon-nas"))
    }
}
