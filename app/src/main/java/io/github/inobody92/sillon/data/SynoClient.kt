package io.github.inobody92.sillon.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

data class Credentials(
    val url: String,
    val account: String,
    val password: String,
    val insecure: Boolean,
    val deviceId: String? = null,
    /** Empreinte SHA-256 du certificat auto-signé accepté par l'utilisateur (obligatoire si [insecure]). */
    val pinnedSha256: String? = null,
    /** ID QuickConnect saisi à la place d'une adresse : sert à retrouver l'adresse du NAS si elle change. */
    val quickConnectId: String? = null,
)

data class ApiInfo(val path: String, val minVersion: Int, val maxVersion: Int)

/** Ce qu'il faut pour reprendre une session sans interroger le NAS : session, APIs découvertes, formats de conversion. */
data class SessionSnapshot(val sid: String, val apis: Map<String, ApiInfo>, val transcodeFormats: Set<String>)

class ApiException(val code: Int, message: String) : Exception(message)

class OtpRequiredException : Exception("Code OTP requis")

data class Album(val name: String, val albumArtist: String, val artist: String, val year: Int) {
    val displayArtist: String get() = albumArtist.ifBlank { artist }
}

data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val track: Int,
    val disc: Int,
    val durationSec: Int,
    val ext: String,
    val genre: String = "",
    val year: Int = 0,
)

data class Artist(val name: String)

data class Genre(val name: String)

data class Playlist(val id: String, val name: String) {
    val isShared: Boolean get() = id.contains("shared")
}

/** Session active, partagée avec le service de lecture (qui vit hors de l'interface). */
object SessionHolder {
    @Volatile
    var client: SynoClient? = null

    /**
     * Rétablit la session depuis les identifiants enregistrés. Sert au service de lecture quand
     * Android a tué l'application : il peut alors reprendre sans que l'interface soit ouverte.
     * Bloquant : à appeler depuis un thread de chargement, jamais depuis le thread principal.
     */
    @Synchronized
    fun restore(context: android.content.Context): SynoClient? {
        client?.let { return it }
        val creds = SecureStore(context).load() ?: return null
        val restored = SynoClient(creds)
        return try {
            kotlinx.coroutines.runBlocking { restored.connect() }
            client = restored
            restored
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Client minimal pour l'API web Audio Station (voir docs/API.md).
 * Les chemins des APIs sont découverts via query.cgi, jamais codés en dur.
 */
class SynoClient(val creds: Credentials) {

    val http: OkHttpClient = buildHttp(creds)

    @Volatile
    private var apis: Map<String, ApiInfo> = emptyMap()

    @Volatile
    var sid: String? = null
        private set

    /** Identifiant de device de confiance, renvoyé par le NAS après un login avec OTP. */
    @Volatile
    var deviceId: String? = creds.deviceId
        private set

    /** Formats vers lesquels le NAS sait convertir à la volée (`mp3`, `wav`), d'après `Info`. */
    @Volatile
    var transcodeFormats: Set<String> = emptySet()
        private set

    val canTranscodeMp3: Boolean get() = "mp3" in transcodeFormats

    fun snapshot(): SessionSnapshot? {
        val current = sid ?: return null
        return if (apis.isEmpty()) null else SessionSnapshot(current, apis, transcodeFormats)
    }

    /**
     * Reprend une session mémorisée, sans réseau. Elle a pu expirer : le premier appel refusé déclenche déjà une
     * reconnexion (voir [call]), et l'application revalide aussi la session en arrière-plan au lancement.
     */
    fun adopt(snapshot: SessionSnapshot) {
        apis = snapshot.apis
        transcodeFormats = snapshot.transcodeFormats
        sid = snapshot.sid
    }

    suspend fun connect(otp: String? = null) = withContext(Dispatchers.IO) {
        discover()
        login(otp)
        loadCapabilities()
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        try {
            post("SYNO.API.Auth", "logout", params = mapOf("session" to "AudioStation"))
        } catch (_: Exception) {
            // Déconnexion au mieux : on oublie la session quoi qu'il arrive.
        }
        sid = null
    }

    /** Appel d'API authentifié. Se reconnecte une fois si la session a expiré. */
    suspend fun call(
        api: String,
        method: String,
        version: Int? = null,
        params: Map<String, String> = emptyMap(),
    ): JSONObject = withContext(Dispatchers.IO) {
        try {
            post(api, method, version, params)
        } catch (e: ApiException) {
            if (e.code in SESSION_ERRORS) {
                login(null)
                post(api, method, version, params)
            } else {
                throw e
            }
        }
    }

    suspend fun albums(
        offset: Int,
        limit: Int,
        artist: String? = null,
        genre: String? = null,
        filter: String? = null,
        sortBy: String? = null,
        sortDirection: String? = null,
    ): List<Album> {
        val params = mutableMapOf("offset" to "$offset", "limit" to "$limit", "library" to "all")
        sortBy?.let { params["sort_by"] = it }
        sortDirection?.let { params["sort_direction"] = it }
        artist?.let { params["artist"] = it }
        genre?.let { params["genre"] = it }
        filter?.let { params["filter"] = it }
        val data = call("SYNO.AudioStation.Album", "list", params = params).getJSONObject("data")
        val arr = data.optJSONArray("albums") ?: return emptyList()
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            Album(
                name = o.optString("name"),
                albumArtist = o.optString("album_artist"),
                artist = o.optString("artist"),
                year = o.optInt("year"),
            )
        }
    }

    suspend fun artists(offset: Int, limit: Int, filter: String? = null): List<Artist> {
        val params = mutableMapOf("offset" to "$offset", "limit" to "$limit", "library" to "all")
        filter?.let { params["filter"] = it }
        val data = call("SYNO.AudioStation.Artist", "list", params = params).getJSONObject("data")
        val arr = data.optJSONArray("artists") ?: return emptyList()
        return List(arr.length()) { i -> Artist(arr.getJSONObject(i).optString("name")) }
            .filter { it.name.isNotBlank() }
    }

    suspend fun genres(): List<Genre> {
        val data = call(
            "SYNO.AudioStation.Genre", "list",
            params = mapOf("offset" to "0", "limit" to "-1", "library" to "all"),
        ).getJSONObject("data")
        val arr = data.optJSONArray("genres") ?: return emptyList()
        return List(arr.length()) { i -> Genre(arr.getJSONObject(i).optString("name")) }
            .filter { it.name.isNotBlank() }
            .sortedBy { it.name.lowercase() }
    }

    // --- tirage aléatoire (mix du jour, radio) ----------------------------------------

    private val totals = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** Nombre de titres de la bibliothèque (ou d'un genre). Mis en cache : la bibliothèque bouge peu. */
    suspend fun songTotal(genre: String?): Int {
        val key = genre.orEmpty()
        totals[key]?.let { return it }
        val params = mutableMapOf("offset" to "0", "limit" to "1", "library" to "all")
        genre?.let { params["genre"] = it }
        val total = call("SYNO.AudioStation.Song", "list", params = params).getJSONObject("data").optInt("total", 0)
        totals[key] = total
        return total
    }

    /** Une page de toute la bibliothèque (sert à construire l'index de recherche). */
    suspend fun libraryPage(offset: Int, limit: Int): List<Song> = songsPage(null, offset, limit)

    /** Nombre de titres, relu auprès du NAS (le cache de [songTotal] ne sert qu'au tirage aléatoire). */
    suspend fun freshSongTotal(): Int {
        totals.remove("")
        return songTotal(null)
    }

    /**
     * Une page de la bibliothèque avec la note de chaque titre (0 = non noté). Sert au profil de goûts :
     * on ne demande ni la durée ni le format, seulement les tags et la note.
     */
    suspend fun libraryRatingsPage(offset: Int, limit: Int): List<Pair<Song, Int>> {
        val params = mapOf(
            "offset" to "$offset", "limit" to "$limit", "library" to "all",
            "additional" to "song_tag,song_rating",
        )
        val arr = call("SYNO.AudioStation.Song", "list", params = params).getJSONObject("data").optJSONArray("songs")
            ?: return emptyList()
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            parseSong(o) to (o.optJSONObject("additional")?.optJSONObject("song_rating")?.optInt("rating", 0) ?: 0)
        }
    }

    private suspend fun songsPage(genre: String?, offset: Int, limit: Int): List<Song> {
        val params = mutableMapOf(
            "offset" to "$offset",
            "limit" to "$limit",
            "library" to "all",
            "additional" to "song_tag,song_audio",
        )
        genre?.let { params["genre"] = it }
        val arr = call("SYNO.AudioStation.Song", "list", params = params).getJSONObject("data").optJSONArray("songs")
            ?: return emptyList()
        return List(arr.length()) { i -> parseSong(arr.getJSONObject(i)) }
    }

    /**
     * Tire [count] titres au hasard, dans les [genres] donnés (toute la bibliothèque si vide).
     * Le NAS ne sait pas trier au hasard : on lit de petites tranches à des positions tirées au sort,
     * en parallèle, pondérées par la taille de chaque genre. [random] rend le tirage reproductible
     * (même graine = même sélection tant que la bibliothèque ne change pas).
     */
    suspend fun randomSongs(count: Int, genres: Collection<String>?, random: kotlin.random.Random): List<Song> =
        kotlinx.coroutines.coroutineScope {
            val sources: List<String?> = if (genres.isNullOrEmpty()) listOf(null) else genres.toList()
            val sized = sources.map { g -> async { g to songTotal(g) } }
                .map { it.await() }
                .filter { it.second > 0 }
            if (sized.isEmpty()) return@coroutineScope emptyList()
            val sum = sized.sumOf { it.second }
            val slice = 3
            // On sur-échantillonne : des doublons et des titres déjà joués seront écartés ensuite.
            val requests = List(kotlin.math.ceil(count * 2.0 / slice).toInt()) {
                var pick = random.nextInt(sum)
                val (genre, total) = sized.first { (_, t) -> if (pick < t) true else { pick -= t; false } }
                genre to random.nextInt(maxOf(1, total - slice + 1))
            }
            requests.map { (g, off) -> async { runCatching { songsPage(g, off, slice) }.getOrDefault(emptyList()) } }
                .flatMap { it.await() }
                .distinctBy { it.id }
                .shuffled(random)
        }

    /**
     * Retrouve des titres à partir de leurs identifiants (dans l'ordre demandé).
     * `Song.getinfo` ne renvoie qu'un titre même si on lui en passe plusieurs (vérifié sur DSM) :
     * on interroge donc chaque identifiant séparément, en parallèle.
     */
    suspend fun songsByIds(ids: List<String>): List<Song> = coroutineScope {
        ids.map { id ->
            async {
                runCatching {
                    val data = call(
                        "SYNO.AudioStation.Song", "getinfo",
                        params = mapOf("id" to id, "library" to "all", "additional" to "song_tag,song_audio"),
                    ).getJSONObject("data")
                    data.optJSONArray("songs")?.optJSONObject(0)?.let { parseSong(it) }
                }.getOrNull()
            }
        }.mapNotNull { it.await() }
    }

    // --- notation ---------------------------------------------------------------------

    /** Note d'un titre (0 = non noté, 1 à 5 étoiles). */
    suspend fun rating(songId: String): Int {
        val data = call(
            "SYNO.AudioStation.Song", "getinfo",
            params = mapOf("id" to songId, "library" to "all", "additional" to "song_rating"),
        ).getJSONObject("data")
        return data.optJSONArray("songs")?.optJSONObject(0)
            ?.optJSONObject("additional")?.optJSONObject("song_rating")?.optInt("rating", 0) ?: 0
    }

    suspend fun setRating(songId: String, rating: Int) {
        call(
            "SYNO.AudioStation.Song", "setrating", version = 2,
            params = mapOf("id" to songId, "rating" to "${rating.coerceIn(0, 5)}"),
        )
    }

    /** Titres dont le titre contient [query]. */
    suspend fun searchSongs(query: String, limit: Int = 50): List<Song> {
        val data = call(
            "SYNO.AudioStation.Song", "search",
            params = mapOf(
                "offset" to "0",
                "limit" to "$limit",
                "library" to "all",
                "title" to query,
                "additional" to "song_tag,song_audio",
            ),
        ).getJSONObject("data")
        val arr = data.optJSONArray("songs") ?: return emptyList()
        return List(arr.length()) { i -> parseSong(arr.getJSONObject(i)) }
    }

    /** Tous les titres d'un artiste, rangés par album puis par piste. */
    suspend fun songsByArtist(artist: String): List<Song> {
        val data = call(
            "SYNO.AudioStation.Song", "list",
            params = mapOf(
                "offset" to "0",
                "limit" to "5000",
                "library" to "all",
                "artist" to artist,
                "additional" to "song_tag,song_audio",
            ),
        ).getJSONObject("data")
        val arr = data.optJSONArray("songs") ?: return emptyList()
        return List(arr.length()) { i -> parseSong(arr.getJSONObject(i)) }
            .sortedWith(compareBy({ it.album.lowercase() }, { it.disc }, { it.track }))
    }

    suspend fun songs(album: Album): List<Song> {
        val params = mutableMapOf(
            "offset" to "0",
            "limit" to "1000",
            "library" to "all",
            "album" to album.name,
            "additional" to "song_tag,song_audio",
        )
        if (album.albumArtist.isNotBlank()) params["album_artist"] = album.albumArtist
        val data = call("SYNO.AudioStation.Song", "list", params = params).getJSONObject("data")
        val arr = data.optJSONArray("songs") ?: return emptyList()
        return List(arr.length()) { i -> parseSong(arr.getJSONObject(i)) }
            .sortedWith(compareBy({ it.disc }, { it.track }, { it.title }))
    }

    // --- playlists -----------------------------------------------------------------------

    suspend fun playlists(): List<Playlist> {
        val data = call(
            PLAYLIST, "list",
            params = mapOf("offset" to "0", "limit" to "-1", "library" to "all"),
        ).getJSONObject("data")
        val arr = data.optJSONArray("playlists") ?: return emptyList()
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            Playlist(o.getString("id"), o.optString("name"))
        }
            .filterNot { it.id.contains("__SYNO_") } // playlists système, non éditables
            .sortedBy { it.name.lowercase() }
    }

    suspend fun playlistSongs(id: String): List<Song> {
        val data = call(
            PLAYLIST, "getinfo",
            params = mapOf(
                "id" to id,
                "library" to libraryOf(id),
                "songs_offset" to "0",
                "songs_limit" to "-1",
                "additional" to "songs,songs_song_tag,songs_song_audio",
            ),
        ).getJSONObject("data")
        val songs = data.optJSONArray("playlists")?.optJSONObject(0)
            ?.optJSONObject("additional")?.optJSONArray("songs") ?: return emptyList()
        return List(songs.length()) { i -> parseSong(songs.getJSONObject(i)) }
    }

    /** Crée une playlist personnelle, éventuellement pré-remplie. Renvoie son identifiant. */
    suspend fun createPlaylist(name: String, songIds: List<String>): String =
        call(
            PLAYLIST, "create",
            params = mapOf("name" to name, "library" to "personal", "songs" to joinIds(songIds)),
        ).getJSONObject("data").getString("id")

    suspend fun renamePlaylist(id: String, newName: String) {
        call(PLAYLIST, "rename", params = mapOf("id" to id, "new_name" to newName))
    }

    suspend fun deletePlaylist(id: String) {
        call(PLAYLIST, "delete", params = mapOf("id" to id))
    }

    /** Ajoute des morceaux en fin de playlist. */
    suspend fun addToPlaylist(id: String, songIds: List<String>) {
        if (songIds.isEmpty()) return
        // Le NAS ne renvoie `songs_total` que si on demande aussi `songs` (vérifié sur DSM).
        val additional = call(
            PLAYLIST, "getinfo",
            params = mapOf(
                "id" to id,
                "library" to libraryOf(id),
                "additional" to "songs,songs_total",
                "songs_offset" to "0",
                "songs_limit" to "1",
            ),
        ).getJSONObject("data").optJSONArray("playlists")?.optJSONObject(0)?.optJSONObject("additional")
        val total = additional?.optInt("songs_total", -1)?.takeIf { it >= 0 }
            ?: playlistSongs(id).size
        updateSongs(id, offset = total, limit = songIds.size, songIds = songIds)
    }

    /** Retire le morceau à la position [index]. */
    suspend fun removeFromPlaylist(id: String, index: Int) {
        updateSongs(id, offset = index, limit = 1, songIds = emptyList())
    }

    /** Réécrit toute la playlist dans l'ordre donné. */
    suspend fun reorderPlaylist(id: String, orderedIds: List<String>) {
        updateSongs(id, offset = 0, limit = orderedIds.size, songIds = orderedIds)
    }

    /** `updatesongs` remplace [limit] morceaux à partir de [offset] par [songIds]. */
    private suspend fun updateSongs(id: String, offset: Int, limit: Int, songIds: List<String>) {
        call(
            PLAYLIST, "updatesongs",
            params = mapOf(
                "id" to id,
                "offset" to "$offset",
                "limit" to "$limit",
                "songs" to joinIds(songIds),
            ),
        )
    }

    private fun libraryOf(id: String) = if (id.contains("shared")) "shared" else "personal"

    /** Les identifiants sont séparés par des virgules : `\` et `,` doivent être échappés. */
    private fun joinIds(ids: List<String>) =
        ids.joinToString(",") { it.replace("\\", "\\\\").replace(",", "\\,") }

    private fun parseSong(o: JSONObject): Song {
        val additional = o.optJSONObject("additional")
        val tag = additional?.optJSONObject("song_tag")
        val audio = additional?.optJSONObject("song_audio")
        return Song(
            id = o.getString("id"),
            title = o.optString("title"),
            artist = tag?.optString("artist").orEmpty(),
            album = tag?.optString("album").orEmpty(),
            albumArtist = tag?.optString("album_artist").orEmpty(),
            track = tag?.optInt("track") ?: 0,
            disc = tag?.optInt("disc", 1) ?: 1,
            durationSec = audio?.optInt("duration") ?: 0,
            ext = o.optString("path").substringAfterLast('.', "").lowercase(),
            genre = tag?.optString("genre").orEmpty(),
            year = tag?.optInt("year") ?: 0,
        )
    }

    /** Pochette d'un morceau (utile hors contexte d'album, par exemple dans une playlist). */
    fun songCoverUrl(songId: String, withSid: Boolean): String? {
        val info = apis["SYNO.AudioStation.Cover"] ?: return null
        val builder = webapiUrl(info.path).newBuilder()
            .addQueryParameter("api", "SYNO.AudioStation.Cover")
            .addQueryParameter("method", "getsongcover")
            .addQueryParameter("version", "1")
            .addQueryParameter("id", songId)
        if (withSid) sid?.let { builder.addQueryParameter("_sid", it) }
        return builder.build().toString()
    }

    /** Se reconnecte avec les identifiants de la session (sid expiré). */
    suspend fun refreshSession() = withContext(Dispatchers.IO) { login(null) }

    /**
     * URL de lecture d'un morceau, avec le sid courant. En qualité « originale » le fichier est servi tel quel ;
     * sinon le NAS le convertit en MP3 au débit demandé.
     */
    fun streamUrl(songId: String, ext: String, quality: AudioQuality = AudioQuality.ORIGINAL): String? {
        val info = apis["SYNO.AudioStation.Stream"] ?: return null
        val builder = webapiUrl(info.path).newBuilder()
            .addQueryParameter("api", "SYNO.AudioStation.Stream")
            .addQueryParameter("version", "1")
            .addQueryParameter("id", songId)
            .addQueryParameter("_sid", sid.orEmpty())
        // Sans conversion MP3 disponible sur le NAS, on lit l'original plutôt que d'échouer.
        if (quality == AudioQuality.ORIGINAL || !canTranscodeMp3) {
            builder.addQueryParameter("method", "stream").addQueryParameter("ext", ".$ext")
        } else {
            builder.addQueryParameter("method", "transcode")
                .addQueryParameter("format", "mp3")
                .addQueryParameter("bitrate", quality.bitrateBps.toString())
                .addQueryParameter("ext", ".mp3")
        }
        return builder.build().toString()
    }

    /** URL de pochette. Le sid est ajouté à part pour ne pas polluer la clé de cache d'image. */
    fun coverUrl(album: Album, withSid: Boolean): String? {
        val info = apis["SYNO.AudioStation.Cover"] ?: return null
        val builder = webapiUrl(info.path).newBuilder()
            .addQueryParameter("api", "SYNO.AudioStation.Cover")
            .addQueryParameter("method", "getcover")
            .addQueryParameter("version", "1")
            .addQueryParameter("album_name", album.name)
            .addQueryParameter("album_artist_name", album.displayArtist)
        if (withSid) sid?.let { builder.addQueryParameter("_sid", it) }
        return builder.build().toString()
    }

    // --- interne ---------------------------------------------------------------------------

    /** Au mieux : une erreur ici ne doit jamais empêcher la connexion. */
    private fun loadCapabilities() {
        transcodeFormats = try {
            val arr = post("SYNO.AudioStation.Info", "getinfo").getJSONObject("data").optJSONArray("transcode")
            if (arr == null) emptySet() else List(arr.length()) { arr.optString(it).lowercase() }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    // --- Fichiers de l'utilisateur sur le NAS (File Station) ----------------------------------

    private var fileSid: String? = null

    /**
     * File Station a sa propre session. Le sid d'Audio Station suffit à lister ou créer un dossier, mais l'envoi d'un
     * fichier y est refusé (401) alors qu'il passe avec une session « FileStation » (vérifié sur le NAS réel).
     */
    private fun fileStationSid(renew: Boolean = false): String {
        if (!renew) fileSid?.let { return it }
        val params = mutableMapOf(
            "account" to creds.account, "passwd" to creds.password, "session" to "FileStation", "format" to "sid",
        )
        deviceId?.let { params["device_id"] = it } // appareil de confiance : pas de nouveau code à deux facteurs
        val json = post("SYNO.API.Auth", "login", params = params, withSid = false, raise = false)
        if (!json.optBoolean("success")) throw ApiException(errorCode(json), "Connexion File Station impossible (code ${errorCode(json)})")
        return json.getJSONObject("data").getString("sid").also { fileSid = it }
    }

    /** Écrit [data] dans [dir]/[name] sur le NAS (dossiers créés au besoin, fichier remplacé s'il existe). */
    suspend fun nasFileWrite(dir: String, name: String, data: ByteArray) = withContext(Dispatchers.IO) {
        val info = apis["SYNO.FileStation.Upload"] ?: throw ApiException(-1, "File Station absent du NAS")
        // Corps multipart écrit à la main, tel que le fait curl : OkHttp ajoute un Content-Length dans chaque partie,
        // ce que l'envoi de DSM ne comprend pas (401 après dix secondes d'attente).
        val boundary = "----SillonBoundary" + System.nanoTime()
        fun field(name: String, value: String) =
            "--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray()
        val payload = java.io.ByteArrayOutputStream().apply {
            write(field("path", dir))
            write(field("create_parents", "true"))
            write(field("overwrite", "true"))
            // Le fichier vient en dernier : File Station lit les paramètres qui le précèdent.
            write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$name\"\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray())
            write(data)
            write("\r\n--$boundary--\r\n".toByteArray())
        }.toByteArray()
        fun attempt(session: String): JSONObject {
            val url = webapiUrl(info.path).newBuilder()
                .addQueryParameter("api", "SYNO.FileStation.Upload")
                .addQueryParameter("version", "2")
                .addQueryParameter("method", "upload")
                .addQueryParameter("_sid", session)
                .build()
            val body = payload.toRequestBody("multipart/form-data; boundary=$boundary".toMediaType())
            return execute(Request.Builder().url(url).post(body).build())
        }
        var json = attempt(fileStationSid())
        if (!json.optBoolean("success") && errorCode(json) in FILE_SESSION_ERRORS) json = attempt(fileStationSid(renew = true))
        if (!json.optBoolean("success")) throw ApiException(errorCode(json), "Écriture sur le NAS refusée (code ${errorCode(json)})")
    }

    /** Contenu de [path] sur le NAS, ou null si le fichier n'existe pas. */
    suspend fun nasFileRead(path: String): ByteArray? = withContext(Dispatchers.IO) {
        val info = apis["SYNO.FileStation.Download"] ?: throw ApiException(-1, "File Station absent du NAS")
        fun attempt(session: String): ByteArray? {
            val url = webapiUrl(info.path).newBuilder()
                .addQueryParameter("api", "SYNO.FileStation.Download")
                .addQueryParameter("version", "2")
                .addQueryParameter("method", "download")
                .addQueryParameter("path", path)
                .addQueryParameter("mode", "download")
                .addQueryParameter("_sid", session)
                .build()
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (resp.code == 404) return null // DSM répond 404 quand le fichier n'existe pas
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                return resp.body?.bytes() ?: ByteArray(0)
            }
        }
        // Une erreur arrive en JSON {"success":false,"error":{"code":…}} ; un fichier, tel quel.
        fun errorOf(bytes: ByteArray): Int? {
            if (bytes.firstOrNull() != '{'.code.toByte()) return null
            val json = runCatching { JSONObject(String(bytes)) }.getOrNull() ?: return null
            return if (json.has("success") && !json.optBoolean("success")) errorCode(json) else null
        }
        var bytes = attempt(fileStationSid() ) ?: return@withContext null
        if (errorOf(bytes) in FILE_SESSION_ERRORS) bytes = attempt(fileStationSid(renew = true)) ?: return@withContext null
        when (val err = errorOf(bytes)) {
            null -> bytes
            FILE_NOT_FOUND -> null
            else -> throw ApiException(err, "Lecture sur le NAS refusée (code $err)")
        }
    }

    private fun webapiUrl(path: String): HttpUrl = "${creds.url}/webapi/$path".toHttpUrl()

    private fun discover() {
        val url = webapiUrl("query.cgi").newBuilder()
            .addQueryParameter("api", "SYNO.API.Info")
            .addQueryParameter("method", "query")
            .addQueryParameter("version", "1")
            .addQueryParameter("query", "all")
            .build()
        val json = execute(Request.Builder().url(url).build())
        if (!json.optBoolean("success")) throw ApiException(errorCode(json), "Découverte des APIs impossible")
        val data = json.getJSONObject("data")
        apis = data.keys().asSequence().associateWith { key ->
            val o = data.getJSONObject(key)
            ApiInfo(o.getString("path"), o.optInt("minVersion", 1), o.optInt("maxVersion", 1))
        }
        if (apis.keys.none { it.startsWith("SYNO.AudioStation.") }) {
            throw ApiException(-1, "Audio Station n'est pas installé ou activé sur ce NAS")
        }
    }

    private fun login(otp: String?) {
        val params = mutableMapOf(
            "account" to creds.account,
            "passwd" to creds.password,
            "session" to "AudioStation",
            "format" to "sid",
        )
        if (otp != null) {
            params["otp_code"] = otp
            params["enable_device_token"] = "yes"
            params["device_name"] = "Sillon"
        }
        deviceId?.let { params["device_id"] = it }
        val json = post("SYNO.API.Auth", "login", params = params, withSid = false, raise = false)
        if (!json.optBoolean("success")) {
            val code = errorCode(json)
            if (code == ERROR_OTP_REQUIRED) throw OtpRequiredException()
            throw ApiException(code, "Échec de connexion (code $code)")
        }
        val data = json.getJSONObject("data")
        sid = data.getString("sid")
        data.optString("did").takeIf { it.isNotBlank() }?.let { deviceId = it }
    }

    private fun post(
        api: String,
        method: String,
        version: Int? = null,
        params: Map<String, String> = emptyMap(),
        withSid: Boolean = true,
        raise: Boolean = true,
    ): JSONObject {
        val info = apis[api] ?: throw ApiException(-1, "API absente du NAS : $api")
        val body = FormBody.Builder()
            .add("api", api)
            .add("method", method)
            .add("version", (version ?: info.maxVersion).toString())
        params.forEach { (k, v) -> body.add(k, v) }
        if (withSid) sid?.let { body.add("_sid", it) }
        val json = execute(Request.Builder().url(webapiUrl(info.path)).post(body.build()).build())
        if (raise && !json.optBoolean("success")) throw ApiException(errorCode(json), "Erreur API $api.$method")
        return json
    }

    private fun execute(request: Request): JSONObject =
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            JSONObject(resp.body?.string() ?: throw IOException("Réponse vide"))
        }

    private fun errorCode(json: JSONObject): Int = json.optJSONObject("error")?.optInt("code", -1) ?: -1

    companion object {
        private const val PLAYLIST = "SYNO.AudioStation.Playlist"
        private val SESSION_ERRORS = setOf(106, 107, 119)
        private val FILE_SESSION_ERRORS = setOf(105, 106, 107, 119)
        private const val FILE_NOT_FOUND = 408
        private const val ERROR_OTP_REQUIRED = 403

        /** Code d'erreur DSM signifiant que la session a expiré ou n'est plus valable. */
        fun isSessionError(code: Int): Boolean = code in SESSION_ERRORS

        /** Clé de cache stable pour une URL : on retire le sid, qui change à chaque connexion. */
        fun stableKey(url: String): String =
            runCatching { url.toHttpUrl().newBuilder().removeAllQueryParameters("_sid").build().toString() }
                .getOrDefault(url) // adresse locale (file://) : déjà stable

        /** Accepte https://hôte[:port] ou hôte[:port] ; refuse le HTTP clair. */
        fun normalizeUrl(input: String): String {
            val t = input.trim().trimEnd('/')
            require(t.isNotEmpty()) { "Adresse vide" }
            require(!t.startsWith("http://", ignoreCase = true)) { "Seul HTTPS est accepté" }
            // Le schéma est insensible à la casse : on le ramène en minuscules pour une adresse unique.
            return if (t.startsWith("https://", ignoreCase = true)) "https://" + t.substring(8) else "https://$t"
        }

        private fun buildHttp(creds: Credentials): OkHttpClient {
            val builder = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
            if (creds.insecure) {
                // NAS à certificat auto-signé : on n'accepte QUE le certificat dont l'empreinte a été confirmée.
                // Sans empreinte (réglage hérité d'une ancienne version), la validation normale s'applique et échoue.
                val pin = creds.pinnedSha256
                if (pin != null) {
                    val trust = PinnedTrustManager(pin)
                    val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
                    builder.sslSocketFactory(ctx.socketFactory, trust).hostnameVerifier { _, _ -> true }
                }
            }
            return builder.build()
        }
    }
}
