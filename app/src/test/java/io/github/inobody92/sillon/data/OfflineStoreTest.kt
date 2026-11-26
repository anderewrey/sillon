package io.github.inobody92.sillon.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class OfflineStoreTest {

    private lateinit var dir: File

    @Before fun setUp() {
        dir = Files.createTempDirectory("offline-test").toFile()
        OfflineStore.initForTest(dir)
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    private fun song(id: String, album: String = "Album") =
        Song(id, "Titre $id", "Artiste", album, "Artiste", 1, 1, 100, "m4a")

    private fun collection(id: String, vararg songs: Song) =
        OfflineCollection(id, "album", id, "Artiste", songs.map { it.id })

    /** Simule la fin d'un téléchargement : le fichier existe et l'index le sait. */
    private fun download(s: Song) {
        OfflineStore.partFile(s).writeText("audio")
        val target = File(dir, "${s.id}.${s.ext}")
        OfflineStore.partFile(s).renameTo(target)
        OfflineStore.markDone(s, target.length())
    }

    @Test fun epinglerMetLesTitresEnAttente() {
        val a = song("a")
        OfflineStore.pin(collection("c1", a), listOf(a))
        assertEquals(1, OfflineStore.state.value.pendingCount)
        assertEquals(a, OfflineStore.nextPending())
        assertNull(OfflineStore.file("a"))
    }

    @Test fun unTitreTelechargeEstLisibleHorsLigne() {
        val a = song("a")
        OfflineStore.pin(collection("c1", a), listOf(a))
        download(a)
        assertNotNull(OfflineStore.file("a"))
        assertEquals(0, OfflineStore.state.value.pendingCount)
        assertEquals(listOf(a), OfflineStore.state.value.downloadedSongs(OfflineStore.state.value.collection("c1")!!))
    }

    @Test fun unTitrePartageEntreDeuxCollectionsSurvitALaSuppressionDeLUne() {
        val a = song("a")
        val b = song("b")
        OfflineStore.pin(collection("c1", a, b), listOf(a, b))
        OfflineStore.pin(collection("c2", a), listOf(a))
        download(a); download(b)

        OfflineStore.unpin("c1")
        assertNotNull("a est encore utilisé par c2", OfflineStore.file("a"))
        assertNull("b n'est plus utilisé par personne", OfflineStore.file("b"))
        assertFalse(File(dir, "b.m4a").exists())
    }

    @Test fun supprimerLaDerniereCollectionEffaceLesFichiersEtLesPochettes() {
        val a = song("a")
        OfflineStore.pin(collection("c1", a), listOf(a))
        download(a)
        OfflineStore.coverFile(a).writeText("jpeg")
        OfflineStore.unpin("c1")
        assertFalse(File(dir, "a.m4a").exists())
        assertFalse(OfflineStore.coverFile(a).exists())
        assertTrue(OfflineStore.state.value.songs.isEmpty())
    }

    @Test fun resynchroniserUneCollectionRetireCeQuiEnEstSorti() {
        val a = song("a"); val b = song("b")
        OfflineStore.pin(collection("pl", a, b), listOf(a, b))
        download(a); download(b)
        // La playlist ne contient plus que « a » sur le NAS.
        OfflineStore.pin(collection("pl", a), listOf(a))
        assertNotNull(OfflineStore.file("a"))
        assertNull(OfflineStore.file("b"))
    }

    @Test fun lIndexSurvitAUnRedemarrage() {
        val a = song("a")
        OfflineStore.pin(collection("c1", a), listOf(a))
        download(a)
        OfflineStore.initForTest(dir) // même dossier : comme si l'application redémarrait
        assertNotNull(OfflineStore.file("a"))
        assertEquals("c1", OfflineStore.state.value.collections.single().id)
    }

    @Test fun unFichierEffaceHorsDeLApplicationNEstPlusCompteCommeTelecharge() {
        val a = song("a")
        OfflineStore.pin(collection("c1", a), listOf(a))
        download(a)
        File(dir, "a.m4a").delete()
        OfflineStore.initForTest(dir)
        assertNull(OfflineStore.file("a"))
    }

    @Test fun lesTitresEnAttenteSontSautesEtNeBloquentPasLaFile() {
        val a = song("a"); val b = song("b")
        OfflineStore.pin(collection("c1", a, b), listOf(a, b))
        OfflineStore.skip(a)
        assertEquals(b, OfflineStore.nextPending())
    }

    @Test fun lAlbumPartageUneSeulePochette() {
        val a = song("a", album = "X"); val b = song("b", album = "X"); val c = song("c", album = "Y")
        assertEquals(OfflineStore.coverKey(a), OfflineStore.coverKey(b))
        assertTrue(OfflineStore.coverKey(a) != OfflineStore.coverKey(c))
    }

    @Test fun laCleDePochetteDeLAlbumEstCelleDeSesTitres() {
        val s = song("a", album = "X")
        assertEquals(OfflineStore.coverKey(s), OfflineStore.coverKey(Album("X", "Artiste", "Autre", 2020)))
        assertNull(OfflineStore.coverKey(Album("", "Artiste", "Artiste", 0)))
    }

    @Test fun laPochetteHorsLigneSeRetrouveParSaCle() {
        val s = song("a", album = "X")
        OfflineStore.coverFile(s).writeText("jpeg")
        assertEquals(OfflineStore.coverFile(s), OfflineStore.coverFileForKey(OfflineStore.coverKey(s)))
        assertNull(OfflineStore.coverFileForKey("../index"))
        assertNull(OfflineStore.coverFileForKey("ffffff00"))
    }
}
