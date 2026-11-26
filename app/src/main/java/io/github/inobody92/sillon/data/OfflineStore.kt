package io.github.inobody92.sillon.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class OfflineSong(val song: Song, val bytes: Long)

/** Un album ou une playlist épinglé(e) pour l'écoute hors-ligne. L'ordre de [songIds] est celui de lecture. */
data class OfflineCollection(
    val id: String,
    val type: String,
    val name: String,
    val subtitle: String,
    val songIds: List<String>,
)

data class OfflineState(
    val songs: Map<String, OfflineSong> = emptyMap(),
    val collections: List<OfflineCollection> = emptyList(),
    val pendingCount: Int = 0,
    val bytes: Long = 0,
) {
    fun isPinned(collectionId: String) = collections.any { it.id == collectionId }

    fun collection(id: String) = collections.firstOrNull { it.id == id }

    /** Titres déjà présents sur l'appareil pour cette collection, dans l'ordre. */
    fun downloadedSongs(c: OfflineCollection): List<Song> = c.songIds.mapNotNull { songs[it]?.song }

    fun downloadedCount(c: OfflineCollection): Int = c.songIds.count { it in songs }
}

/**
 * Index des téléchargements : quels titres sont sur l'appareil, lesquels restent à récupérer,
 * et à quels albums / playlists ils appartiennent. Un titre est supprimé quand plus aucune collection n'en a besoin.
 */
object OfflineStore {

    private lateinit var root: File
    private lateinit var covers: File
    private lateinit var index: File

    private val songs = LinkedHashMap<String, OfflineSong>()
    private val pending = LinkedHashMap<String, Song>()
    private val collections = LinkedHashMap<String, OfflineCollection>()

    private val _state = MutableStateFlow(OfflineState())
    val state: StateFlow<OfflineState> = _state.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (::root.isInitialized) return
        setUp(File(context.applicationContext.filesDir, "offline"))
    }

    /** Réinitialise le magasin dans [dir] (tests) : simule aussi un redémarrage de l'application. */
    @Synchronized
    internal fun initForTest(dir: File) = setUp(dir)

    private fun setUp(dir: File) {
        root = dir.apply { mkdirs() }
        covers = File(root, "covers").apply { mkdirs() }
        index = File(root, "index.json")
        songs.clear()
        pending.clear()
        collections.clear()
        load()
        publish()
    }

    fun albumKey(album: Album) = "album:${album.albumArtist}|${album.name}"

    fun playlistKey(playlistId: String) = "playlist:$playlistId"

    // --- fichiers ---------------------------------------------------------------------

    private fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9_.-]"), "_")

    /** Fichier audio d'un titre, s'il est entièrement téléchargé. */
    @Synchronized
    fun file(songId: String): File? =
        songs[songId]?.let { fileFor(it.song) }?.takeIf { it.exists() }

    private fun fileFor(song: Song) = File(root, "${safe(song.id)}.${safeExtension(song.ext).ifBlank { "bin" }}")

    fun partFile(song: Song) = File(root, "${safe(song.id)}.part")

    /** Les titres d'un même album partagent une seule pochette, pour ne pas la dupliquer. */
    fun coverKey(song: Song): String =
        Integer.toHexString((song.album.ifBlank { song.id } + "|" + song.albumArtist).hashCode())

    fun coverFile(song: Song) = File(covers, "${coverKey(song)}.jpg")

    /**
     * Clé de la pochette d'un album : la même que [coverKey] pour ses titres (même nom d'album, même artiste
     * de l'album). Null si l'album n'a pas de nom : ses titres utilisent alors leur propre identifiant.
     */
    fun coverKey(album: Album): String? =
        album.name.takeIf { it.isNotBlank() }?.let { Integer.toHexString((it + "|" + album.albumArtist).hashCode()) }

    /** Pochette téléchargée pour la clé [key] (voir [coverKey]), si elle existe. */
    fun coverFileForKey(key: String): File? {
        if (!COVER_KEY.matches(key)) return null
        return File(covers, "$key.jpg").takeIf { it.isFile && it.length() > 0 }
    }

    /** Format de [coverKey] : `Integer.toHexString`. Tout le reste est refusé (aucun chemin construit par un tiers). */
    private val COVER_KEY = Regex("^[0-9a-f]{1,8}$")

    // --- état -------------------------------------------------------------------------

    @Synchronized
    fun nextPending(): Song? = pending.values.firstOrNull()

    @Synchronized
    fun markDone(song: Song, bytes: Long) {
        pending.remove(song.id)
        songs[song.id] = OfflineSong(song, bytes)
        save()
        publish()
    }

    /** Abandonne un titre impossible à récupérer, pour ne pas bloquer la file. */
    @Synchronized
    fun skip(song: Song) {
        pending.remove(song.id)
        save()
        publish()
    }

    /** Épingle une collection : les titres manquants passent en attente de téléchargement. */
    @Synchronized
    fun pin(collection: OfflineCollection, collectionSongs: List<Song>) {
        collections[collection.id] = collection
        collectionSongs.forEach { s -> if (s.id !in songs && s.id !in pending) pending[s.id] = s }
        // Resynchronisation : ce qui n'est plus dans la collection et n'est utilisé par aucune autre disparaît.
        cleanup()
        save()
        publish()
    }

    @Synchronized
    fun unpin(collectionId: String) {
        collections.remove(collectionId)
        cleanup()
        save()
        publish()
    }

    @Synchronized
    fun clearAll() {
        collections.clear()
        cleanup()
        save()
        publish()
    }

    private fun cleanup() {
        val needed = collections.values.flatMap { it.songIds }.toSet()
        songs.keys.filter { it !in needed }.forEach { id ->
            songs.remove(id)?.let { fileFor(it.song).delete() }
        }
        pending.keys.filter { it !in needed }.forEach { pending.remove(it) }
        val usedCovers = songs.values.map { coverKey(it.song) }.toSet()
        covers.listFiles()?.filter { it.nameWithoutExtension !in usedCovers }?.forEach { it.delete() }
        root.listFiles()?.filter { it.extension == "part" }
            ?.filter { f -> pending.values.none { safe(it.id) == f.nameWithoutExtension } }
            ?.forEach { it.delete() }
    }

    private fun publish() {
        _state.value = OfflineState(
            songs = LinkedHashMap(songs),
            collections = collections.values.toList(),
            pendingCount = pending.size,
            bytes = songs.values.sumOf { it.bytes },
        )
    }

    // --- persistance ------------------------------------------------------------------

    private fun save() {
        try {
            val json = JSONObject()
                .put("songs", JSONArray().also { arr ->
                    songs.values.forEach { arr.put(it.song.toJson().put("bytes", it.bytes)) }
                })
                .put("pending", pending.values.toList().toJsonArray())
                .put("collections", JSONArray().also { arr ->
                    collections.values.forEach { c ->
                        arr.put(
                            JSONObject().put("id", c.id).put("type", c.type).put("name", c.name)
                                .put("subtitle", c.subtitle).put("songIds", JSONArray(c.songIds)),
                        )
                    }
                })
            // Écriture atomique : un plantage en cours d'écriture ne doit pas corrompre l'index.
            val tmp = File(root, "index.json.tmp")
            tmp.writeText(json.toString())
            tmp.renameTo(index)
        } catch (_: Exception) {
        }
    }

    private fun load() {
        try {
            if (!index.exists()) return
            val json = JSONObject(index.readText())
            json.optJSONArray("songs")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val song = songFromJson(o)
                    // Un fichier disparu (effacé hors de l'app) ne doit pas être considéré comme téléchargé.
                    if (fileFor(song).exists()) songs[song.id] = OfflineSong(song, o.optLong("bytes"))
                }
            }
            json.optJSONArray("pending")?.let { songsFromJson(it).forEach { s -> if (s.id !in songs) pending[s.id] = s } }
            json.optJSONArray("collections")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val ids = o.getJSONArray("songIds").let { a -> List(a.length()) { a.getString(it) } }
                    collections[o.getString("id")] = OfflineCollection(
                        o.getString("id"), o.optString("type"), o.optString("name"), o.optString("subtitle"), ids,
                    )
                }
            }
            // Les titres d'une collection dont le fichier a disparu repartent en téléchargement.
            // (Leur fiche est reconstruite depuis l'index, jamais redemandée au NAS.)
        } catch (_: Exception) {
            songs.clear(); pending.clear(); collections.clear()
        }
    }
}
