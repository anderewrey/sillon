package io.github.inobody92.sillon.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import io.github.inobody92.sillon.data.Album
import io.github.inobody92.sillon.data.Song

/** Fabrique des éléments de lecture, partagée par l'application et la voiture (Android Auto). */
object MediaItems {

    const val SCHEME = "sillon"
    const val EXTRA_PARENT = "sillon.parent"
    const val EXTRA_RADIO = "sillon.radio"

    // Minuteur de sommeil (commande de session et état publié dans les extras de la session)
    const val EXTRA_SLEEP_MODE = "sillon.sleep.mode"
    const val EXTRA_SLEEP_END = "sillon.sleep.end"
    const val EXTRA_SLEEP_MINUTES = "sillon.sleep.minutes"
    const val SLEEP_TIME = "time"
    const val SLEEP_TRACK = "track"
    const val SLEEP_QUEUE = "queue"
    const val SLEEP_OFF = "off"

    fun song(
        song: Song,
        album: Album?,
        artwork: Uri?,
        parentId: String? = null,
        radio: String? = null,
    ): MediaItem {
        val uri = Uri.Builder()
            .scheme(SCHEME)
            .authority("song")
            .appendPath("${song.id}.${song.ext}")
            .appendQueryParameter("id", song.id)
            .build()
        val extras = Bundle().apply {
            parentId?.let { putString(EXTRA_PARENT, it) }
            radio?.let { putString(EXTRA_RADIO, it) }
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist.ifBlank { album?.displayArtist.orEmpty() })
            .setAlbumTitle(song.album.ifBlank { album?.name.orEmpty() })
            .setAlbumArtist(song.albumArtist.ifBlank { album?.displayArtist.orEmpty() })
            .setTrackNumber(song.track.takeIf { it > 0 })
            .setDurationMs(song.durationSec.takeIf { it > 0 }?.let { it * 1000L })
            .setArtworkUri(artwork)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setExtras(extras)
            .build()
        return MediaItem.Builder()
            .setMediaId(song.id)
            .setUri(uri)
            .setMimeType(mimeFor(song.ext))
            .setMediaMetadata(metadata)
            .build()
    }

    /** Dossier de navigation (Android Auto) : non lisible directement, ou lisible en entier si [playable]. */
    fun folder(id: String, title: String, subtitle: String? = null, artwork: Uri? = null, playable: Boolean = false, type: Int = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setArtworkUri(artwork)
                    .setIsBrowsable(!playable)
                    .setIsPlayable(playable)
                    .setMediaType(type)
                    .build(),
            )
            .build()

    fun mimeFor(ext: String): String? = when (ext) {
        "m4a", "mp4", "aac" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "ogg", "oga" -> "audio/ogg"
        "opus" -> "audio/opus"
        "wav" -> "audio/wav"
        else -> null
    }
}
