package `fun`.tryliner.android.playback

import android.content.Intent
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import androidx.annotation.OptIn
import kotlin.math.pow
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import `fun`.tryliner.android.LinerApplication
import `fun`.tryliner.android.R
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class LinerPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var audioCache: SimpleCache? = null
    private var chunkedDataSourceFactory: ChunkedGoogleVideoDataSource.Factory? = null
    private var exoPlayer: ExoPlayer? = null
    private var remotePlayer: RemoteMediaPlayer? = null
    private var serviceScope: CoroutineScope? = null
    private var exoPlayerListener: Player.Listener? = null
    private var playerControllerRef: PlayerController? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var dynamicsProcessing: DynamicsProcessing? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        val container = (applicationContext as LinerApplication).container
        val lanConnectManager = container.lanConnectManager
        val playerController = container.playerController
        playerControllerRef = playerController

        val cacheDir = File(cacheDir, "audio_cache")
        val cache = SimpleCache(
            cacheDir,
            LeastRecentlyUsedCacheEvictor(AUDIO_CACHE_MAX_BYTES),
            StandaloneDatabaseProvider(this),
        )
        audioCache = cache

        val dataSourceFactory = ChunkedGoogleVideoDataSource.Factory()
        chunkedDataSourceFactory = dataSourceFactory
        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(dataSourceFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 50_000,
                /* bufferForPlaybackMs = */ 250,
                /* bufferForPlaybackAfterRebufferMs = */ 500,
            )
            .build()

        val player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(LocalFirstDataSource.Factory(cacheDataSourceFactory))
                    .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(/* minimumLoadableRetryCount = */ 6))
            )
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        exoPlayer = player

        setupAudioNormalization(player)

        val remote = RemoteMediaPlayer(lanConnectManager)
        remotePlayer = remote

        mediaSession = MediaSession.Builder(this, player)
            .build()

        playerController.onBeforeLocalPlayback = {
            val session = mediaSession
            if (session != null && session.player !== player) {
                session.setPlayer(player)
            }
        }
        playerController.onApplyLoudnessDb = { loudnessDb ->
            applyLoudnessNormalization(player, loudnessDb)
        }

        val listener = object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                syncSessionPlayer(lanConnectManager.remoteState.value)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                syncSessionPlayer(lanConnectManager.remoteState.value)
            }
        }
        exoPlayerListener = listener
        player.addListener(listener)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        serviceScope = scope
        scope.launch {
            combine(lanConnectManager.remoteState, playerController.state) { remoteState, _ ->
                remoteState
            }.collect { remoteState ->
                syncSessionPlayer(remoteState)
            }
        }
        syncSessionPlayer(lanConnectManager.remoteState.value)

        val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(NOTIFICATION_CHANNEL_ID)
            .setChannelName(R.string.playback_notification_channel_name)
            .build()
            .apply {
                setSmallIcon(R.drawable.ic_liner_notification)
            }
        setMediaNotificationProvider(notificationProvider)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null ||
            !player.playWhenReady ||
            player.mediaItemCount == 0 ||
            player.playbackState == Player.STATE_ENDED
        ) {
            stopSelf()
        }
    }

    private fun syncSessionPlayer(remoteState: RemotePlaybackState?) {
        val session = mediaSession ?: return
        val local = exoPlayer ?: return
        val remote = remotePlayer ?: return
        val isLocalBuffering = playerControllerRef?.state?.value?.isBuffering == true
        val isLocalActive = local.playWhenReady ||
            local.playbackState == Player.STATE_BUFFERING ||
            isLocalBuffering

        val shouldUseRemote = !isLocalActive &&
            remoteState?.track != null &&
            (remoteState.isPlaying || remoteState.isSlave || session.player === remote)

        if (shouldUseRemote) {
            remote.updateState(remoteState)
            if (session.player !== remote) {
                session.setPlayer(remote)
            }
        } else {
            if (remoteState?.track == null) {
                remote.updateState(null)
            }
            if (session.player !== local) {
                session.setPlayer(local)
            }
        }
    }

    override fun onDestroy() {
        serviceScope?.cancel()
        serviceScope = null
        playerControllerRef?.onBeforeLocalPlayback = null
        playerControllerRef?.onApplyLoudnessDb = null
        playerControllerRef = null
        runCatching { loudnessEnhancer?.release() }
        loudnessEnhancer = null
        runCatching { dynamicsProcessing?.release() }
        dynamicsProcessing = null
        exoPlayerListener?.let { listener ->
            exoPlayer?.removeListener(listener)
        }
        exoPlayerListener = null
        mediaSession?.run {
            release()
            mediaSession = null
        }
        exoPlayer?.release()
        exoPlayer = null
        remotePlayer?.release()
        remotePlayer = null
        audioCache?.release()
        audioCache = null
        chunkedDataSourceFactory?.release()
        chunkedDataSourceFactory = null
        super.onDestroy()
    }

    private fun setupAudioNormalization(player: ExoPlayer) {
        val sessionId = player.audioSessionId
        if (sessionId == C.AUDIO_SESSION_ID_UNSET || sessionId == 0) return

        runCatching {
            loudnessEnhancer = LoudnessEnhancer(sessionId).apply {
                setTargetGain(0)
                enabled = true
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                val config = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    /* channelCount = */ 2,
                    /* preEqInUse = */ false,
                    /* preEqBandCount = */ 0,
                    /* mbcInUse = */ false,
                    /* mbcBandCount = */ 0,
                    /* postEqInUse = */ false,
                    /* postEqBandCount = */ 0,
                    /* limiterInUse = */ true,
                ).build()
                dynamicsProcessing = DynamicsProcessing(/* priority = */ 0, sessionId, config).apply {
                    setLimiterAllChannelsTo(
                        DynamicsProcessing.Limiter(
                            /* inUse = */ true,
                            /* enabled = */ true,
                            /* linkGroup = */ 0,
                            /* attackTime = */ 2.0f,
                            /* releaseTime = */ 60.0f,
                            /* ratio = */ 4.0f,
                            /* threshold = */ -1.5f,
                            /* postGain = */ 0.0f,
                        )
                    )
                    enabled = true
                }
            }
        }
    }

    private fun applyLoudnessNormalization(player: ExoPlayer, loudnessDb: Float?) {
        if (loudnessEnhancer == null && dynamicsProcessing == null) {
            setupAudioNormalization(player)
        }
        if (loudnessDb != null && loudnessDb.isFinite()) {
            if (loudnessDb > 0f) {
                val linear = 10f.pow(-loudnessDb / 20f).coerceIn(0.25f, 1.0f)
                player.volume = linear
                runCatching { loudnessEnhancer?.setTargetGain(0) }
            } else {
                player.volume = 1.0f
                val boostMb = (-loudnessDb * 100f).toInt().coerceIn(0, 800)
                runCatching { loudnessEnhancer?.setTargetGain(boostMb) }
            }
        } else {
            // SoundCloud / local / offline tracks without YT loudnessDb: gentle lift + limiter
            player.volume = 0.92f
            runCatching { loudnessEnhancer?.setTargetGain(250) }
        }
    }

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "liner_playback"
        private const val AUDIO_CACHE_MAX_BYTES = 512L * 1024L * 1024L
    }
}
