package io.github.inobody92.sillon.playback

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import android.content.Context
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.inobody92.sillon.data.AppSettings
import io.github.inobody92.sillon.widget.WidgetUpdater
import io.github.inobody92.sillon.data.Hidden
import io.github.inobody92.sillon.data.HiddenRules
import io.github.inobody92.sillon.data.Likes
import io.github.inobody92.sillon.data.ListenEvent
import io.github.inobody92.sillon.data.ListenStats
import io.github.inobody92.sillon.data.TasteStore
import io.github.inobody92.sillon.data.classifyListen
import io.github.inobody92.sillon.data.OfflineStore
import io.github.inobody92.sillon.data.PlayHistory
import io.github.inobody92.sillon.data.SessionHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import okhttp3.Call
import java.io.IOException

/**
 * Lecture en arrière-plan, et point d'entrée d'Android Auto (bibliothèque navigable + recherche vocale).
 *
 * Les éléments de file ont une adresse interne `sillon://song/<id>.<ext>?id=<id>` ; elle est transformée en vraie
 * URL de streaming au moment de la lecture, avec le sid courant, pour ne jamais garder une URL périmée.
 *
 * Radio : les titres générés portent le marqueur [MediaItems.EXTRA_RADIO] ; tant que le titre en cours en est un,
 * le service prolonge la file avant qu'elle ne soit vide. Dès que l'utilisateur lance autre chose, la radio s'arrête.
 */
@UnstableApi
class PlaybackService : MediaLibraryService() {

    private var session: MediaLibrarySession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val equalizer = EqualizerEffect()
    private lateinit var tree: LibraryTree
    private lateinit var callers: CallerPolicy

    /** Morceau pour lequel on a déjà tenté une reconnexion (évite les boucles). */
    private var retriedMediaId: String? = null
    private var refillingRadio = false
    private var saveJob: Job? = null
    private lateinit var prefetchSources: CacheDataSource.Factory
    private val prefetchJobs = mutableMapOf<String, Job>()

    // Minuteur de sommeil : état gardé dans le service pour survivre à la fermeture de l'écran.
    private var sleepJob: Job? = null
    private var sleepMode = ""
    private var sleepEndAt = 0L

    private fun activeClient() = SessionHolder.client ?: SessionHolder.restore(applicationContext)

    override fun onCreate() {
        super.onCreate()
        AppSettings.init(applicationContext)
        OfflineStore.init(applicationContext)
        Likes.init(applicationContext)
        TasteStore.init(applicationContext) // goûts, titres masqués et statistiques d'écoute (aussi sans l'application ouverte)
        tree = LibraryTree(applicationContext)
        callers = CallerPolicy(applicationContext)

        // Réutilise le client HTTP de la session (certificat auto-signé compris).
        val callFactory = Call.Factory { request ->
            val client = activeClient() ?: throw IOException("Pas de session Sillon active")
            client.http.newCall(request)
        }
        // Réseau : l'adresse interne sillon:// devient la vraie URL de streaming, avec le sid courant.
        // Seules ces adresses passent par le client du NAS : toute autre URL est refusée.
        val networkResolver = ResolvingDataSource.Resolver { spec: DataSpec ->
            if (spec.uri.scheme != MediaItems.SCHEME) {
                throw IOException("Adresse refusée : seules les adresses sillon:// sont lues")
            } else {
                val id = spec.uri.getQueryParameter("id") ?: throw IOException("Identifiant de morceau manquant")
                val client = activeClient() ?: throw IOException("Pas de session Sillon active")
                val ext = spec.uri.lastPathSegment.orEmpty().substringAfterLast('.', "")
                val url = client.streamUrl(id, ext, AppSettings.currentQuality(applicationContext))
                    ?: throw IOException("API de streaming absente du NAS")
                spec.withUri(Uri.parse(url))
            }
        }
        // Cache disque : la clé est l'adresse interne (stable, sans sid) et la qualité demandée.
        val cacheFactory = CacheDataSource.Factory()
            .setCache(AudioCache.get(applicationContext))
            .setUpstreamDataSourceFactory(
                ResolvingDataSource.Factory(OkHttpDataSource.Factory(callFactory), networkResolver),
            )
            .setCacheKeyFactory { spec -> "${spec.uri}#${AppSettings.currentQuality(applicationContext).name}" }
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        prefetchSources = cacheFactory
        // Titre téléchargé : on lit le fichier local, sans cache ni NAS (marche hors connexion).
        val offlineResolver = ResolvingDataSource.Resolver { spec: DataSpec ->
            if (spec.uri.scheme != MediaItems.SCHEME) {
                spec
            } else {
                val id = spec.uri.getQueryParameter("id")
                val local = id?.let { OfflineStore.file(it) }
                if (local != null) spec.withUri(Uri.fromFile(local)) else spec
            }
        }
        val dataSourceFactory = ResolvingDataSource.Factory(DefaultDataSource.Factory(this, cacheFactory), offlineResolver)

        // L'audio décodé passe par un « tap » (lecture seule) qui alimente l'égaliseur de l'écran de lecture.
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessors(arrayOf<AudioProcessor>(TeeAudioProcessor(SpectrumTap())))
                .build()
        }
        val player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        // Égaliseur d'Android : suit la session audio du lecteur et les réglages de l'utilisateur.
        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) = equalizer.attach(audioSessionId)
        })
        equalizer.attach(player.audioSessionId)
        scope.launch {
            combine(AppSettings.eqEnabled, AppSettings.eqGains) { on, gains -> on to gains }
                .collect { (on, gains) -> equalizer.update(on, gains) }
        }

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                retriedMediaId = null
                refreshLikeButton()
                updateSleepForTransition(player)
                prefetchNext(player)
                // Historique d'écoute (local) : alimente le mix du jour et la radio.
                mediaItem?.mediaId?.takeIf { it.isNotBlank() }?.let { PlayHistory.record(applicationContext, it) }
                // Un titre masqué après avoir été mis en file : on ne l'entend pas. Un titre choisi à la main
                // (changement de liste de lecture) est lu quand même, c'est une demande explicite.
                if (mediaItem != null && isHiddenItem(mediaItem) &&
                    (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
                ) {
                    if (player.hasNextMediaItem()) player.seekToNextMediaItem() else player.pause()
                }
                maybeRefillRadio(player)
                scheduleSave(player)
            }

            // Ce qu'on a écouté du titre qui se termine : fini (+) ou passé (−), pour affiner les goûts.
            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                val automatic = reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION
                if (!automatic && reason != Player.DISCONTINUITY_REASON_SEEK) return
                val item = old.mediaItem ?: return
                if (!automatic && old.mediaItemIndex == new.mediaItemIndex) return // avance rapide dans le même titre
                val id = item.mediaId.takeIf { it.isNotBlank() } ?: return
                val event = classifyListen(old.positionMs, item.mediaMetadata.durationMs ?: 0L, automatic) ?: return
                ListenStats.record(id, completed = event == ListenEvent.COMPLETED)
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                maybeRefillRadio(player)
                scheduleSave(player)
                prefetchNext(player)
            }

            // Un titre est prêt : c'est le moment de préparer les suivants.
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) prefetchNext(player)
            }

            // « Fin du titre » / « fin de la file » : le lecteur s'est mis en pause tout seul, le minuteur a fait son travail.
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM &&
                    (sleepMode == SLEEP_TRACK || sleepMode == SLEEP_QUEUE)
                ) {
                    cancelSleep(player)
                }
            }

            // Pause ou fin : c'est le bon moment pour mémoriser la position exacte.
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) saveNow(player)
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = scheduleSave(player)

            override fun onRepeatModeChanged(repeatMode: Int) = scheduleSave(player)

            // Session NAS expirée en cours de route : on se reconnecte une fois puis on reprend.
            override fun onPlayerError(error: PlaybackException) {
                val mediaId = player.currentMediaItem?.mediaId ?: return
                if (retriedMediaId == mediaId) return
                retriedMediaId = mediaId
                val client = SessionHolder.client ?: return
                scope.launch {
                    val ok = runCatching { client.refreshSession() }.isSuccess
                    if (ok) player.prepare()
                }
            }
        })

        // Toucher la notification (ou le lecteur réduit de l'écran verrouillé) ramène dans l'application, sur la lecture.
        val openApp = android.app.PendingIntent.getActivity(
            this, 0, io.github.inobody92.sillon.MainActivity.openPlayerIntent(this),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, player, LibraryCallback()).setSessionActivity(openApp).build()
        WidgetUpdater(applicationContext, player, scope).start()   // widget de l'écran d'accueil

        restoreQueue(player)
        refreshLikeButton()
        // Le cœur de la notification et de la voiture suit l'état « liké » du titre en cours.
        scope.launch { Likes.state.collect { refreshLikeButton() } }
        // Pendant la lecture, on mémorise la position toutes les 20 s (un plantage ne fait pas perdre plus).
        scope.launch {
            while (true) {
                delay(20_000)
                if (player.isPlaying) saveNow(player)
            }
        }
    }

    /**
     * Précharge dans le cache les prochains titres de la file (en tenant compte de l'aléatoire et de la répétition),
     * pour que l'enchaînement ne dépende plus du réseau et que le NAS ne soit sollicité qu'une fois.
     */
    private fun prefetchNext(player: Player) {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return
        val targets = mutableListOf<MediaItem>()
        var index = player.currentMediaItemIndex
        for (i in 0 until PREFETCH_AHEAD) {
            index = timeline.getNextWindowIndex(index, player.repeatMode, player.shuffleModeEnabled)
            if (index == C.INDEX_UNSET) break
            targets += player.getMediaItemAt(index)
        }
        val wanted = targets.map { it.mediaId }.toSet()
        prefetchJobs.keys.filter { it !in wanted }.forEach { prefetchJobs.remove(it)?.cancel() }
        for (item in targets) {
            val id = item.mediaId
            val uri = item.localConfiguration?.uri ?: continue
            if (prefetchJobs.containsKey(id) || OfflineStore.file(id) != null) continue
            val job = scope.launch(Dispatchers.IO) {
                try {
                    val source = prefetchSources.createDataSource()
                    val writer = CacheWriter(source, DataSpec.Builder().setUri(uri).build(), ByteArray(64 * 1024), null)
                    coroutineContext[Job]?.invokeOnCompletion { writer.cancel() }
                    writer.cache()
                } catch (_: Exception) {
                    // Réseau coupé ou titre indisponible : la lecture normale réessaiera le moment venu.
                } finally {
                    prefetchJobs.remove(id)
                }
            }
            prefetchJobs[id] = job
        }
    }

    // --- minuteur de sommeil ------------------------------------------------------------

    private fun publishSleep() {
        session?.setSessionExtras(
            Bundle().apply {
                putString(EXTRA_SLEEP_MODE, sleepMode)
                putLong(EXTRA_SLEEP_END, sleepEndAt)
            },
        )
    }

    /** Demande au lecteur de se mettre en pause à la fin du titre en cours (propre à ExoPlayer). */
    private fun pauseAtEnd(player: Player, value: Boolean) {
        (player as? ExoPlayer)?.pauseAtEndOfMediaItems = value
    }

    private fun cancelSleep(player: Player) {
        sleepJob?.cancel()
        sleepJob = null
        sleepMode = ""
        sleepEndAt = 0L
        pauseAtEnd(player, false)
        player.volume = 1f
        publishSleep()
    }

    /** [mode] : [SLEEP_TIME] (dans [minutes] minutes), [SLEEP_TRACK] (fin du titre), [SLEEP_QUEUE] (fin de la file) ou arrêt. */
    private fun startSleep(player: Player, mode: String, minutes: Int) {
        cancelSleep(player)
        when (mode) {
            SLEEP_TIME -> {
                if (minutes <= 0) return
                sleepMode = SLEEP_TIME
                sleepEndAt = System.currentTimeMillis() + minutes * 60_000L
                sleepJob = scope.launch {
                    // Le son baisse doucement pendant les dernières secondes avant de s'arrêter.
                    delay((sleepEndAt - System.currentTimeMillis() - SLEEP_FADE_MS).coerceAtLeast(0))
                    val steps = 20
                    for (i in steps - 1 downTo 0) {
                        player.volume = i / steps.toFloat()
                        delay(SLEEP_FADE_MS / steps)
                    }
                    player.pause()
                    cancelSleep(player)
                }
            }
            SLEEP_TRACK -> {
                sleepMode = SLEEP_TRACK
                pauseAtEnd(player, true)
            }
            SLEEP_QUEUE -> {
                sleepMode = SLEEP_QUEUE
                pauseAtEnd(player, !player.hasNextMediaItem())
            }
        }
        publishSleep()
    }

    /** En mode « fin de la file », on ne s'arrête qu'à la fin du dernier titre. */
    private fun updateSleepForTransition(player: Player) {
        if (sleepMode == SLEEP_QUEUE) pauseAtEnd(player, !player.hasNextMediaItem())
    }

    /** Bouton cœur : affiché par la notification, l'écran de verrouillage et Android Auto. */
    private fun likeButton(liked: Boolean): CommandButton =
        CommandButton.Builder(if (liked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
            .setDisplayName(if (liked) "Retirer des titres likés" else "Liker")
            .setSessionCommand(LIKE_COMMAND)
            .build()

    private fun refreshLikeButton() {
        val s = session ?: return
        val id = s.player.currentMediaItem?.mediaId
        s.setMediaButtonPreferences(
            if (id == null) ImmutableList.of() else ImmutableList.of(likeButton(Likes.isLiked(id))),
        )
    }

    private fun scheduleSave(player: Player) {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(1_000) // regroupe les rafales d'événements (ajout de plusieurs titres, etc.)
            saveNow(player)
        }
    }

    private fun saveNow(player: Player) {
        val json = QueueStore.snapshot(player) ?: return
        scope.launch(Dispatchers.IO) { QueueStore.write(applicationContext, json) }
    }

    /** Remet la file d'attente de la dernière session, en pause, sans rien charger tant qu'on ne lance pas la lecture. */
    private fun restoreQueue(player: Player) {
        scope.launch {
            val saved = withContext(Dispatchers.IO) { QueueStore.load(applicationContext) } ?: return@launch
            if (player.mediaItemCount > 0) return@launch // l'utilisateur a déjà lancé autre chose entre-temps
            // Les pochettes passent par CoverProvider : inutile d'attendre la session du NAS ici.
            player.setMediaItems(buildItems(saved), saved.index, saved.positionMs)
            player.shuffleModeEnabled = saved.shuffle
            player.repeatMode = saved.repeatMode
        }
    }

    private fun buildItems(saved: SavedQueue): List<MediaItem> =
        saved.items.map { MediaItems.song(it.song, null, CoverUris.song(applicationContext, it.song), it.parent, it.radio) }

    /** Titre masqué (lui-même ou par une règle sur son artiste ou son album). */
    private fun isHiddenItem(item: MediaItem): Boolean {
        val md = item.mediaMetadata
        return Hidden.isHidden(item.mediaId) ||
            HiddenRules.matches(md.artist?.toString().orEmpty(), md.albumArtist?.toString().orEmpty(), md.albumTitle?.toString().orEmpty())
    }

    /** Radio sans fin : ajoute des titres quand il en reste moins de cinq après celui en cours. */
    private fun maybeRefillRadio(player: Player) {
        val mood = player.currentMediaItem?.mediaMetadata?.extras?.getString(MediaItems.EXTRA_RADIO) ?: return
        val remaining = player.mediaItemCount - player.currentMediaItemIndex - 1
        if (remaining >= RADIO_LOW_WATER || refillingRadio) return
        refillingRadio = true
        scope.launch {
            try {
                val inQueue = List(player.mediaItemCount) { player.getMediaItemAt(it).mediaId }.toSet()
                val more = tree.radioBatch(mood, LibraryTree.RADIO_REFILL, exclude = inQueue)
                // La radio n'a de sens que si l'utilisateur n'a pas lancé autre chose entre-temps.
                val stillRadio = player.currentMediaItem?.mediaMetadata?.extras?.getString(MediaItems.EXTRA_RADIO) == mood
                if (stillRadio && more.isNotEmpty()) player.addMediaItems(more)
            } catch (_: Exception) {
                // Réseau momentanément indisponible : on réessaiera au prochain changement de titre.
            } finally {
                refillingRadio = false
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        session?.player?.let { saveNow(it) }
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        equalizer.release()
        prefetchJobs.values.forEach { it.cancel() }
        session?.player?.let { p -> QueueStore.snapshot(p)?.let { QueueStore.write(applicationContext, it) } }
        session?.run {
            player.release()
            release()
        }
        session = null
        scope.cancel()
        super.onDestroy()
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val caller = callers.classify(controller)
            Log.i(TAG_CALLERS, "connexion de ${controller.packageName} (uid ${controller.uid}) : $caller")
            if (caller == CallerClass.UNKNOWN) {
                // Application inconnue : commandes de lecture seulement (lecture, pause, suivant, volume…).
                // Ni parcours de la bibliothèque, ni ajout à la file, ni « j'aime ». Jamais de refus complet :
                // un contrôleur légitime mais imprévu (accessoire, montre d'une autre marque) garde l'essentiel.
                val playback = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                    .remove(Player.COMMAND_SET_MEDIA_ITEM)
                    .remove(Player.COMMAND_CHANGE_MEDIA_ITEMS)
                    .build()
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(SessionCommands.EMPTY)
                    .setAvailablePlayerCommands(playback)
                    .build()
            }
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(LIKE_COMMAND)
                .add(SLEEP_COMMAND)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == LIKE_COMMAND.customAction) {
                val id = session.player.currentMediaItem?.mediaId
                if (id != null) {
                    scope.launch {
                        val client = withContext(Dispatchers.IO) { activeClient() }
                        if (client != null) Likes.toggle(client, id)
                    }
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (customCommand.customAction == SLEEP_COMMAND.customAction) {
                startSleep(session.player, args.getString(EXTRA_SLEEP_MODE).orEmpty(), args.getInt(EXTRA_SLEEP_MINUTES, 0))
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(tree.rootItem(), params))

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
            runCatching { tree.item(mediaId) }.getOrNull()
                ?.let { LibraryResult.ofItem(it, null) }
                ?: LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            try {
                LibraryResult.ofItemList(ImmutableList.copyOf(tree.children(parentId, page, pageSize)), params)
            } catch (_: Exception) {
                LibraryResult.ofError(LibraryResult.RESULT_ERROR_IO)
            }
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = scope.future {
            val count = runCatching { tree.search(query).size }.getOrDefault(0)
            session.notifySearchResultChanged(browser, query, count, params)
            LibraryResult.ofVoid()
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            try {
                val all = tree.search(query)
                LibraryResult.ofItemList(ImmutableList.copyOf(all.drop(page * pageSize).take(pageSize)), params)
            } catch (_: Exception) {
                LibraryResult.ofError(LibraryResult.RESULT_ERROR_IO)
            }
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val saved = withContext(Dispatchers.IO) { QueueStore.load(applicationContext) }
                ?: throw UnsupportedOperationException("Rien à reprendre")
            MediaSession.MediaItemsWithStartPosition(buildItems(saved), saved.index, saved.positionMs)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = scope.future { resolve(mediaItems, callers.isSelf(controller)).toMutableList() }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val single = mediaItems.singleOrNull()
            val parent = single?.let { it.mediaMetadata.extras?.getString(MediaItems.EXTRA_PARENT) ?: tree.parentOf(it.mediaId) }
            if (single != null && parent != null) {
                // Un titre choisi dans un album ou une playlist (depuis la voiture) : on joue tout le contenu à partir de lui.
                val siblings = runCatching { tree.expand(parent) }.getOrNull().orEmpty()
                val index = siblings.indexOfFirst { it.mediaId == single.mediaId }
                if (index >= 0) {
                    return@future MediaSession.MediaItemsWithStartPosition(siblings, index, startPositionMs)
                }
            }
            val resolved = resolve(mediaItems, callers.isSelf(controller))
            val start = if (resolved.size == mediaItems.size) startIndex else 0
            MediaSession.MediaItemsWithStartPosition(
                resolved,
                start.coerceIn(0, (resolved.size - 1).coerceAtLeast(0)),
                startPositionMs,
            )
        }
    }

    /**
     * Remplace les éléments « sans adresse » (identifiants de conteneurs, requêtes vocales) par de vrais titres.
     * Les éléments venus d'une autre application ([fromSelf] faux) sont toujours reconstruits depuis leur identifiant :
     * leur adresse et leurs métadonnées sont ignorées (sinon le lecteur téléchargerait n'importe quelle URL avec le
     * client du NAS).
     */
    private suspend fun resolve(items: List<MediaItem>, fromSelf: Boolean): List<MediaItem> {
        val out = mutableListOf<MediaItem>()
        for (item in items) {
            if (fromSelf && item.localConfiguration != null) {
                out += item
                continue
            }
            val query = item.requestMetadata.searchQuery
            val expanded = try {
                if (query != null) {
                    tree.playbackForQuery(query)
                } else {
                    tree.expand(item.mediaId) ?: tree.item(item.mediaId)?.let { listOf(it) }
                }
            } catch (_: Exception) {
                null
            }
            if (expanded != null) out += expanded
        }
        return out
    }

    private companion object {
        const val EXTRA_SLEEP_MODE = MediaItems.EXTRA_SLEEP_MODE
        const val EXTRA_SLEEP_END = MediaItems.EXTRA_SLEEP_END
        const val EXTRA_SLEEP_MINUTES = MediaItems.EXTRA_SLEEP_MINUTES
        const val SLEEP_TIME = MediaItems.SLEEP_TIME
        const val SLEEP_TRACK = MediaItems.SLEEP_TRACK
        const val SLEEP_QUEUE = MediaItems.SLEEP_QUEUE
        const val RADIO_LOW_WATER = 5
        const val PREFETCH_AHEAD = 2
        const val SLEEP_FADE_MS = 8_000L
        const val TAG_CALLERS = "SillonAppelants"
        val LIKE_COMMAND = SessionCommand("sillon.TOGGLE_LIKE", Bundle.EMPTY)
        val SLEEP_COMMAND = SessionCommand("sillon.SLEEP", Bundle.EMPTY)
    }
}
