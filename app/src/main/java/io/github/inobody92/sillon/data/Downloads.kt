package io.github.inobody92.sillon.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Épinglage d'albums et de playlists pour l'écoute hors-ligne, et planification des téléchargements. */
object Downloads {

    private const val WORK_NAME = "sillon-downloads"

    suspend fun pinAlbum(context: Context, client: SynoClient, album: Album) {
        val songs = client.songs(album)
        OfflineStore.init(context)
        OfflineStore.pin(
            OfflineCollection(OfflineStore.albumKey(album), "album", album.name, album.displayArtist, songs.map { it.id }),
            songs,
        )
        schedule(context)
    }

    suspend fun pinPlaylist(context: Context, client: SynoClient, playlist: Playlist) {
        val songs = client.playlistSongs(playlist.id)
        OfflineStore.init(context)
        OfflineStore.pin(
            OfflineCollection(OfflineStore.playlistKey(playlist.id), "playlist", playlist.name, "Playlist", songs.map { it.id }),
            songs,
        )
        schedule(context)
    }

    fun unpin(context: Context, collectionId: String) {
        OfflineStore.init(context)
        OfflineStore.unpin(collectionId)
    }

    /**
     * Lance (ou relance) le téléchargement des titres en attente. Avec [replace], la contrainte réseau est
     * recalculée : à utiliser quand l'utilisateur change l'option « Wi-Fi uniquement ».
     */
    fun schedule(context: Context, replace: Boolean = false) {
        AppSettings.init(context)
        val network = if (AppSettings.downloadWifiOnly.value) NetworkType.UNMETERED else NetworkType.CONNECTED
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            WORK_NAME,
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }
}

class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        OfflineStore.init(ctx)
        AppSettings.init(ctx)
        if (OfflineStore.nextPending() == null) return Result.success()

        val client = SessionHolder.client
            ?: withContext(Dispatchers.IO) { SessionHolder.restore(ctx) }
            ?: return Result.retry()

        var done = 0
        while (true) {
            // On relit la file à chaque tour : des titres peuvent avoir été ajoutés pendant le téléchargement.
            val song = OfflineStore.nextPending() ?: break
            setForeground(foregroundInfo(ctx, song.title, done, done + OfflineStore.state.value.pendingCount))
            val ok = try {
                fetch(client, song)
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                return Result.retry() // réseau perdu : WorkManager réessaiera plus tard
            }
            if (ok) done++ else OfflineStore.skip(song)
        }
        return Result.success()
    }

    private suspend fun fetch(client: SynoClient, song: Song): Boolean = withContext(Dispatchers.IO) {
        val part = OfflineStore.partFile(song)
        var saved = false
        // Deux essais : si la session a expiré, le NAS répond du JSON au lieu de l'audio.
        for (attempt in 1..2) {
            val url = client.streamUrl(song.id, song.ext, AudioQuality.ORIGINAL) ?: return@withContext false
            client.http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                val type = resp.header("Content-Type").orEmpty()
                if (type.contains("json") || type.startsWith("text")) {
                    client.refreshSession()
                } else {
                    resp.body?.byteStream()?.use { input ->
                        part.outputStream().use { out ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                            }
                        }
                    }
                    saved = part.length() > 0
                }
            }
            if (saved) break
        }
        if (!saved) {
            part.delete()
            return@withContext false
        }
        val target = java.io.File(part.parentFile, part.nameWithoutExtension + "." + safeExtension(song.ext).ifBlank { "bin" })
        if (!part.renameTo(target)) {
            part.delete()
            return@withContext false
        }
        fetchCover(client, song)
        OfflineStore.markDone(song, target.length())
        true
    }

    /** La pochette est facultative : son échec ne doit pas compter comme un échec du titre. */
    private fun fetchCover(client: SynoClient, song: Song) {
        val file = OfflineStore.coverFile(song)
        if (file.exists() && file.length() > 0) return
        try {
            val url = client.songCoverUrl(song.id, withSid = true) ?: return
            client.http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                val type = resp.header("Content-Type").orEmpty()
                if (resp.isSuccessful && type.startsWith("image")) {
                    resp.body?.bytes()?.takeIf { it.isNotEmpty() }?.let { file.writeBytes(it) }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun foregroundInfo(ctx: Context, title: String, done: Int, total: Int): ForegroundInfo {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Téléchargements", NotificationManager.IMPORTANCE_LOW),
        )
        val notification = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Téléchargement hors-ligne")
            .setContentText("$title (${done + 1}/$total)")
            .setProgress(total, done, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private companion object {
        const val CHANNEL = "downloads"
        const val NOTIFICATION_ID = 2001
    }
}
