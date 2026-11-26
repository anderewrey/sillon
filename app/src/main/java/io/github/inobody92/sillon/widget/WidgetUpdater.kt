package io.github.inobody92.sillon.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.glance.appwidget.updateAll
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch

/**
 * Tient le widget à jour : à chaque changement de titre ou d'état de lecture, enregistre un instantané (titre, artiste,
 * pochette réduite) et redessine le widget. Les événements rapprochés sont regroupés en une seule mise à jour.
 */
@UnstableApi
class WidgetUpdater(
    context: Context,
    private val player: Player,
    private val scope: CoroutineScope,
) {
    private val context = context.applicationContext
    private val bitmapLoader = DataSourceBitmapLoader(this.context)
    private var job: Job? = null
    private var savedCoverKey: String? = WidgetStore.load(this.context).coverKey

    fun start() {
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(
                        Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_PLAY_WHEN_READY_CHANGED,
                        Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_MEDIA_METADATA_CHANGED,
                        Player.EVENT_PLAYBACK_STATE_CHANGED, Player.EVENT_TIMELINE_CHANGED,
                    )
                ) schedule()
            }
        })
        schedule()
    }

    private fun schedule() {
        job?.cancel()
        job = scope.launch {
            delay(300)
            refresh()
        }
    }

    private suspend fun refresh() {
        val item = player.currentMediaItem
        val md = player.mediaMetadata
        val coverKey = (md.artworkUri?.toString() ?: item?.mediaId)?.takeIf { item != null }
        if (coverKey != savedCoverKey) {
            // Adresse content:// stable (sans sid) : la pochette n'est rechargée que si elle change vraiment.
            WidgetStore.saveCover(context, loadCover())
            savedCoverKey = coverKey
        }
        WidgetStore.save(
            context,
            WidgetSnapshot(
                hasItem = item != null,
                title = md.title?.toString().orEmpty(),
                artist = md.artist?.toString().orEmpty(),
                playing = player.isPlaying || (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING),
                coverKey = coverKey,
            ),
        )
        Widgets.updateAll(context)
    }

    private suspend fun loadCover(): Bitmap? = try {
        val md = player.mediaMetadata
        val bitmap = md.artworkData?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            ?: md.artworkUri?.let { uri: Uri -> bitmapLoader.loadBitmap(uri).await() }
        // Un widget a une mémoire limitée : on garde une pochette carrée de 384 px au plus.
        bitmap?.let { if (it.width > 384) Bitmap.createScaledBitmap(it, 384, 384 * it.height / it.width, true) else it }
    } catch (_: Exception) {
        null
    }
}
