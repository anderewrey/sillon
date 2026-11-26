package io.github.inobody92.sillon.data

import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okio.BufferedSource
import okio.buffer
import okio.sink
import java.io.File
import java.io.IOException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * Pochette demandée par une adresse `content://` (voir `CoverProvider`). Ces adresses sortent de l'application
 * (Android Auto, notification, widget) : elles ne contiennent jamais de sid, seulement de quoi retrouver l'image.
 */
sealed class CoverRequest {
    /** Clé de la pochette téléchargée pour l'écoute hors-ligne ([OfflineStore.coverKey]), si connue et valide. */
    abstract val offlineKey: String?

    /** Texte qui identifie la pochette sur le NAS : sert à nommer le fichier du cache. */
    internal abstract val identity: String

    data class Song(val id: String, override val offlineKey: String?) : CoverRequest() {
        override val identity get() = "song:$id"
    }

    /** [artist] est l'artiste affiché de l'album ([Album.displayArtist]), comme pour `getcover`. */
    data class Album(val name: String, val artist: String, override val offlineKey: String?) : CoverRequest() {
        override val identity get() = "album:$name\u0000$artist"
    }

    companion object {
        const val PATH_SONG = "song"
        const val PATH_ALBUM = "album"
        const val PARAM_ID = "id"
        const val PARAM_NAME = "name"
        const val PARAM_ARTIST = "artist"
        const val PARAM_KEY = "k"

        private val SONG_ID = Regex("^[A-Za-z0-9_]{1,64}$")
        private const val MAX_TEXT = 512

        /**
         * Lit une demande venue d'une adresse : [path] est le premier segment, [param] lit un paramètre.
         * Ces valeurs viennent potentiellement d'une autre application : tout ce qui est inattendu est refusé.
         * La clé hors ligne est validée par [OfflineStore.coverFileForKey], seul endroit où elle sert.
         */
        fun parse(path: String?, param: (String) -> String?): CoverRequest? {
            val key = param(PARAM_KEY)
            return when (path) {
                PATH_SONG -> param(PARAM_ID)?.takeIf { SONG_ID.matches(it) }?.let { Song(it, key) }
                PATH_ALBUM -> {
                    val name = param(PARAM_NAME)?.takeIf { it.isNotBlank() && it.length <= MAX_TEXT } ?: return null
                    val artist = param(PARAM_ARTIST).orEmpty().takeIf { it.length <= MAX_TEXT } ?: return null
                    Album(name, artist, key)
                }
                else -> null
            }
        }
    }
}

/** Accès à la session du NAS, remplaçable dans les tests. */
interface SessionAccess {
    /** Session déjà ouverte, sans attendre. */
    fun current(): SynoClient?

    /** Rouvre la session depuis les identifiants enregistrés. Bloquant ; null si impossible. */
    fun restore(): SynoClient?

    /** Renouvelle le sid de [client] (nouveau login). Bloquant ; lève une exception en cas d'échec. */
    fun renew(client: SynoClient)

    /** Adresse + compte de la session enregistrée, sans réseau : sépare les caches de deux NAS ou comptes. */
    fun owner(): String
}

/**
 * Une seule ouverture ou reconnexion à la fois, et une pause après un échec : une grille de pochettes (Android Auto)
 * ne doit pas déclencher des dizaines de logins, que le NAS pourrait sanctionner par un blocage automatique.
 */
class SessionGate(
    private val access: SessionAccess,
    private val clock: () -> Long = System::currentTimeMillis,
    private val backoffMs: Long = 60_000,
    private val waitMs: Long = 3_000,
) {
    private val lock = ReentrantLock()

    fun owner(): String = access.owner()
    private var failedAt: Long? = null

    private fun inBackoff(): Boolean = failedAt?.let { clock() - it < backoffMs } ?: false

    /**
     * Les appels arrivent sur les fils Binder du fournisseur : on n'attend pas indéfiniment qu'un autre fil
     * finisse sa reconnexion (NAS injoignable = délais longs). Après [waitMs], on renonce : pas de pochette.
     */
    private inline fun <T> guarded(block: () -> T?): T? {
        if (!lock.tryLock(waitMs, TimeUnit.MILLISECONDS)) return null
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    fun client(): SynoClient? {
        access.current()?.let { return it }
        return guarded {
            access.current() ?: if (inBackoff()) null else access.restore().also { failedAt = if (it == null) clock() else null }
        }
    }

    /** Appelé quand le NAS a refusé [staleSid]. Si un autre fil a déjà renouvelé la session, on la réutilise. */
    fun renew(client: SynoClient, staleSid: String?): SynoClient? = guarded {
        when {
            client.sid != staleSid -> client
            inBackoff() -> null
            else -> try {
                access.renew(client)
                failedAt = null
                client
            } catch (_: Exception) {
                failedAt = clock()
                null
            }
        }
    }
}

/**
 * Trouve le fichier d'une pochette : d'abord celle téléchargée pour l'écoute hors-ligne, puis le cache disque,
 * enfin le NAS (le sid est ajouté ici, il ne sort jamais de l'application). Rien d'Android ici : testable sur
 * l'ordinateur.
 */
class CoverSource(
    private val offlineCover: (String) -> File?,
    private val cacheDir: File,
    private val gate: SessionGate,
    private val maxBytes: Long = 5L * 1024 * 1024,
    private val cacheCapBytes: Long = 50L * 1024 * 1024,
    private val timeoutSeconds: Long = 10,
    maxConcurrentFetches: Int = 4,
    private val waitMs: Long = 3_000,
    private val unreachableMs: Long = 30_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val fetches = Semaphore(maxConcurrentFetches)

    /** Verrous par pochette, répartis sur un nombre fixe d'objets : pas de table qui grossit sans fin. */
    private val locks = Array(32) { Any() }

    /** Après une erreur réseau (NAS injoignable, hors du réseau local), on ne retente pas avant cette heure. */
    @Volatile private var unreachableUntil = 0L

    /** Le fichier de la pochette, ou null si elle est introuvable (réseau absent, pas de pochette, refus du NAS). */
    fun open(request: CoverRequest): File? {
        request.offlineKey?.let(offlineCover)?.let { return it }
        val name = cacheName(request)
        cached(name)?.let { return it }
        // Tous les titres d'un album partagent la même adresse : une seule requête au NAS pour eux tous.
        synchronized(locks[(name.hashCode() and Int.MAX_VALUE) % locks.size]) {
            cached(name)?.let { return it }
            if (clock() < unreachableUntil) return null
            // Fils Binder : attente bornée si d'autres pochettes sont déjà en cours de chargement.
            if (!fetches.tryAcquire(waitMs, TimeUnit.MILLISECONDS)) return null
            try {
                return fetch(request, name)
            } finally {
                fetches.release()
            }
        }
    }

    private fun cacheName(request: CoverRequest): String =
        sha256Hex((gate.owner() + "\u0000" + request.identity).toByteArray()) + ".img"

    /** Date d'usage mise à jour au plus une fois par heure : pas une écriture disque à chaque affichage. */
    private fun cached(name: String): File? =
        File(cacheDir, name).takeIf { it.length() > 0 }?.also {
            if (clock() - it.lastModified() > TOUCH_MS) it.setLastModified(clock())
        }

    private fun fetch(request: CoverRequest, name: String): File? {
        val client = gate.client() ?: return null
        val sid = client.sid
        return when (val first = download(client, request, name)) {
            is Outcome.Image -> first.file
            Outcome.SessionExpired -> {
                val renewed = gate.renew(client, sid) ?: return null
                (download(renewed, request, name) as? Outcome.Image)?.file
            }
            Outcome.Failed -> null
        }
    }

    private sealed class Outcome {
        class Image(val file: File) : Outcome()
        object SessionExpired : Outcome()
        object Failed : Outcome()
    }

    private fun download(client: SynoClient, request: CoverRequest, name: String): Outcome {
        val url = when (request) {
            is CoverRequest.Song -> client.songCoverUrl(request.id, withSid = true)
            is CoverRequest.Album -> client.coverUrl(Album(request.name, request.artist, "", 0), withSid = true)
        } ?: return Outcome.Failed
        val call = client.http.newCall(Request.Builder().url(url).build())
        call.timeout().timeout(timeoutSeconds, TimeUnit.SECONDS)
        return try {
            call.execute().use { resp ->
                val body = resp.body ?: return Outcome.Failed
                val type = resp.header("Content-Type").orEmpty()
                when {
                    !resp.isSuccessful -> Outcome.Failed
                    type.startsWith("image/") -> store(body.source(), name)?.let { Outcome.Image(it) } ?: Outcome.Failed
                    // Le NAS répond en JSON (statut 200) quand il refuse : session expirée ou autre erreur.
                    else -> {
                        val text = body.source().let { src ->
                            if (src.request(MAX_ERROR_BYTES + 1)) return Outcome.Failed
                            src.readUtf8()
                        }
                        if (SynoClient.isSessionError(text)) Outcome.SessionExpired else Outcome.Failed
                    }
                }
            }
        } catch (_: IOException) {
            unreachableUntil = clock() + unreachableMs
            Outcome.Failed
        }
    }

    /** Écrit l'image dans un fichier temporaire puis le renomme : jamais de pochette à moitié écrite dans le cache. */
    private fun store(source: BufferedSource, name: String): File? {
        if (source.request(maxBytes + 1) || source.exhausted()) return null // trop grosse ou vide
        cacheDir.mkdirs()
        val temp = File.createTempFile("cover", ".part", cacheDir)
        try {
            temp.sink().buffer().use { it.writeAll(source) }
            val target = File(cacheDir, name)
            if (!temp.renameTo(target)) return null
            trim()
            return target
        } finally {
            temp.delete()
        }
    }

    /** Garde le cache sous [cacheCapBytes] : on efface les pochettes les moins récemment utilisées. */
    private fun trim() {
        val files = cacheDir.listFiles()?.filter { it.isFile } ?: return
        files.filter { it.name.endsWith(".part") && clock() - it.lastModified() > STALE_PART_MS }.forEach { it.delete() }
        val images = files.filter { it.name.endsWith(".img") }.sortedBy { it.lastModified() }
        var total = images.sumOf { it.length() }
        for (f in images) {
            if (total <= cacheCapBytes) break
            total -= f.length()
            f.delete()
        }
    }

    private companion object {
        const val MAX_ERROR_BYTES = 64L * 1024
        const val STALE_PART_MS = 10 * 60_000L
        const val TOUCH_MS = 60 * 60_000L
    }
}

/** La vraie session de l'application : [SessionHolder] et les identifiants enregistrés. */
class AppSessionAccess(private val context: android.content.Context) : SessionAccess {
    override fun current(): SynoClient? = SessionHolder.client
    override fun restore(): SynoClient? = SessionHolder.restore(context)
    override fun renew(client: SynoClient) = runBlocking { client.refreshSession() }
    override fun owner(): String = SecureStore(context).let { it.lastUrl() + "|" + it.lastAccount() }
}
