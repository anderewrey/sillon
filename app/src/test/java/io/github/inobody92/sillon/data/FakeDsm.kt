package io.github.inobody92.sillon.data

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

/**
 * Faux Audio Station : reproduit ce qu'on a observé du vrai NAS (chemins découverts par `query.cgi`, réponses
 * `{success, data}`, codes d'erreur, sémantique de `updatesongs`) pour tester le client sans réseau.
 */
class FakeDsm : Dispatcher() {

    /** Mot de passe attendu ; `otp` non nul = double authentification obligatoire. */
    var password = "secret"
    var otp: String? = null
    var transcode: List<String> = emptyList()

    /** Nombre de requêtes authentifiées à refuser avec le code 119 (session expirée) avant de réussir. */
    var expireSessionTimes = 0

    var sidCounter = 0

    /** Pochettes servies par `cover.cgi` : `song:<id>` ou `album:<nom>|<artiste>` -> octets de l'image. */
    val covers = mutableMapOf<String, ByteArray>()

    /** Sid refusé par `cover.cgi` (session révoquée côté NAS) jusqu'au prochain login. */
    var revokedSid: String? = null

    /** Nombre de requêtes reçues par `cover.cgi` et de logins réussis ou non. */
    @Volatile var coverRequests = 0
    @Volatile var logins = 0
    val requests = mutableListOf<Map<String, String>>()

    /** Playlists : id -> (nom, titres dans l'ordre). */
    val playlists = linkedMapOf<String, Pair<String, MutableList<String>>>()
    private var nextPlaylist = 1

    private val apiPaths = mapOf(
        "SYNO.API.Auth" to "entry.cgi",
        "SYNO.AudioStation.Info" to "AudioStation/info.cgi",
        "SYNO.AudioStation.Album" to "AudioStation/album.cgi",
        "SYNO.AudioStation.Song" to "AudioStation/song.cgi",
        "SYNO.AudioStation.Playlist" to "AudioStation/playlist.cgi",
        "SYNO.AudioStation.Stream" to "AudioStation/stream.cgi",
        "SYNO.AudioStation.Cover" to "AudioStation/cover.cgi",
    )

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty()
        if (path.startsWith("/webapi/query.cgi")) return ok(discovery())
        if (path.startsWith("/webapi/AudioStation/cover.cgi")) return cover(request)
        val params = parse(request.body.readUtf8())
        requests += params
        return when (params["api"]) {
            "SYNO.API.Auth" -> auth(params)
            else -> authenticated(params)
        }
    }

    private fun discovery(): JSONObject {
        val data = JSONObject()
        apiPaths.forEach { (api, p) -> data.put(api, JSONObject().put("path", p).put("minVersion", 1).put("maxVersion", 3)) }
        return JSONObject().put("success", true).put("data", data)
    }

    /** `cover.cgi` en GET : l'image si le sid est le dernier délivré, sinon l'erreur 119 en JSON (statut 200). */
    @Synchronized
    private fun cover(request: RecordedRequest): MockResponse {
        coverRequests++
        val url = request.requestUrl ?: return ok(error(101))
        val sid = url.queryParameter("_sid")
        if (expireSessionTimes > 0) {
            expireSessionTimes--
            return ok(error(119))
        }
        if (sid == null || sid != "SID$sidCounter" || sid == revokedSid) return ok(error(119))
        val key = when (url.queryParameter("method")) {
            "getsongcover" -> "song:" + url.queryParameter("id")
            "getcover" -> "album:" + url.queryParameter("album_name") + "|" + url.queryParameter("album_artist_name")
            else -> return ok(error(103))
        }
        val bytes = covers[key] ?: return ok(error(103))
        return MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(bytes))
    }

    @Synchronized
    private fun auth(p: Map<String, String>): MockResponse {
        if (p["method"] == "logout") return ok(success(JSONObject()))
        logins++
        if (p["passwd"] != password) return ok(error(400))
        if (otp != null && p["otp_code"] != otp && p["device_id"] == null) return ok(error(403))
        sidCounter++
        return ok(success(JSONObject().put("sid", "SID$sidCounter").put("did", "DEVICE")))
    }

    private fun authenticated(p: Map<String, String>): MockResponse {
        if (expireSessionTimes > 0) {
            expireSessionTimes--
            return ok(error(119))
        }
        return when (p["api"] to p["method"]) {
            "SYNO.AudioStation.Info" to "getinfo" ->
                ok(success(JSONObject().put("transcode", JSONArray(transcode))))
            "SYNO.AudioStation.Album" to "list" ->
                ok(success(JSONObject().put("albums", JSONArray().put(
                    JSONObject().put("name", "13").put("album_artist", "Indochine").put("artist", "Indochine").put("year", 2017),
                )).put("total", 1)))
            "SYNO.AudioStation.Playlist" to "list" -> ok(success(JSONObject().put("playlists", JSONArray().also { arr ->
                playlists.forEach { (id, v) -> arr.put(JSONObject().put("id", id).put("name", v.first)) }
            })))
            "SYNO.AudioStation.Playlist" to "getinfo" -> playlistInfo(p)
            "SYNO.AudioStation.Playlist" to "create" -> {
                val id = "playlist_personal_normal/${nextPlaylist++}"
                playlists[id] = p["name"].orEmpty() to split(p["songs"]).toMutableList()
                ok(success(JSONObject().put("id", id)))
            }
            "SYNO.AudioStation.Playlist" to "updatesongs" -> updateSongs(p)
            "SYNO.AudioStation.Playlist" to "delete" -> {
                playlists.remove(p["id"])
                ok(success(JSONObject().put("errors", JSONArray())))
            }
            else -> ok(error(103))
        }
    }

    private fun playlistInfo(p: Map<String, String>): MockResponse {
        val entry = playlists[p["id"]] ?: return ok(error(550))
        val songs = JSONArray().also { arr ->
            entry.second.forEach { arr.put(JSONObject().put("id", it).put("title", it).put("path", "/music/$it.m4a").put("type", "file")) }
        }
        val additional = JSONObject().put("songs", songs).put("songs_total", entry.second.size)
        return ok(success(JSONObject().put("playlists", JSONArray().put(JSONObject().put("id", p["id"]).put("name", entry.first).put("additional", additional)))))
    }

    /** `updatesongs` remplace `limit` titres à partir de `offset` par la liste donnée (comportement observé sur le NAS). */
    private fun updateSongs(p: Map<String, String>): MockResponse {
        val entry = playlists[p["id"]] ?: return ok(error(550))
        val offset = p["offset"]!!.toInt()
        val limit = p["limit"]!!.toInt()
        val list = entry.second
        val end = (offset + limit).coerceAtMost(list.size)
        for (i in offset until end) list.removeAt(offset)
        list.addAll(offset.coerceAtMost(list.size), split(p["songs"]))
        return ok(success(JSONObject()))
    }

    /** Liste séparée par des virgules ; `\\` protège le caractère suivant (comme le vrai NAS). */
    private fun split(csv: String?): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var escaped = false
        for (ch in csv.orEmpty()) {
            when {
                escaped -> { cur.append(ch); escaped = false }
                ch == '\\' -> escaped = true
                ch == ',' -> { out += cur.toString(); cur.clear() }
                else -> cur.append(ch)
            }
        }
        out += cur.toString()
        return out.filter { it.isNotBlank() }
    }

    private fun parse(body: String): Map<String, String> =
        body.split("&").filter { it.contains("=") }.associate {
            val (k, v) = it.split("=", limit = 2)
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }

    private fun success(data: JSONObject) = JSONObject().put("success", true).put("data", data)
    private fun error(code: Int) = JSONObject().put("success", false).put("error", JSONObject().put("code", code))
    private fun ok(json: JSONObject) = MockResponse().setHeader("Content-Type", "application/json").setBody(json.toString())
}
