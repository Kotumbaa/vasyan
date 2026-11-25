package `fun`.tryliner.android.playback

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlin.math.roundToInt

/**
 * Media3 [SimpleBasePlayer] implementation representing remote desktop playback
 * over LAN Connect so Android renders a remote MediaSession notification and routes
 * hardware volume rocker presses to desktop volume.
 */
@OptIn(UnstableApi::class)
class RemoteMediaPlayer(
    private val lanConnectManager: LanConnectManager,
) : SimpleBasePlayer(Looper.getMainLooper()) {

    private var currentRemote: RemotePlaybackState? = null

    private val remoteDeviceInfo = DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE)
        .setMaxVolume(MAX_VOLUME)
        .build()

    private val supportedCommands = Player.Commands.Builder()
        .addAll(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_PREPARE,
            Player.COMMAND_STOP,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_METADATA,
            Player.COMMAND_GET_DEVICE_VOLUME,
            Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
            Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
        )
        .build()

    fun updateState(remote: RemotePlaybackState?) {
        if (Looper.myLooper() == applicationLooper) {
            currentRemote = remote
            invalidateState()
        } else {
            Handler(applicationLooper).post {
                currentRemote = remote
                invalidateState()
            }
        }
    }

    override fun getState(): State {
        val remote = currentRemote
        val track = remote?.track
        val deviceVolume = ((remote?.volume ?: 1f) * MAX_VOLUME).roundToInt().coerceIn(0, MAX_VOLUME)

        if (remote == null || track == null) {
            return State.Builder()
                .setAvailableCommands(supportedCommands)
                .setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .setPlaybackState(Player.STATE_IDLE)
                .setPlaylist(emptyList())
                .setDeviceInfo(remoteDeviceInfo)
                .setDeviceVolume(deviceVolume)
                .setIsDeviceMuted(deviceVolume == 0)
                .build()
        }

        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist.ifBlank { null })
            .apply {
                track.coverUrl?.takeIf { it.isNotBlank() }?.let { url ->
                    setArtworkUri(Uri.parse(url))
                }
            }
            .build()

        val mediaItem = MediaItem.Builder()
            .setMediaId(track.id)
            .setMediaMetadata(metadata)
            .build()

        val durationMs = (remote.durationMs.takeIf { it > 0L } ?: track.durationMs).coerceAtLeast(0L)
        val durationUs = if (durationMs > 0L) durationMs * 1000L else C.TIME_UNSET

        val mediaItemData = MediaItemData.Builder(track.id)
            .setMediaItem(mediaItem)
            .setMediaMetadata(metadata)
            .setDurationUs(durationUs)
            .setIsSeekable(durationMs > 0L)
            .build()

        val basePositionMs = remote.positionMs.coerceAtLeast(0L)
        val updatedAt = remote.updatedAt
        val positionSupplier = if (remote.isPlaying) {
            PositionSupplier {
                val elapsed = (System.currentTimeMillis() - updatedAt).coerceAtLeast(0L)
                val pos = basePositionMs + elapsed
                if (durationMs > 0L) pos.coerceAtMost(durationMs) else pos
            }
        } else {
            PositionSupplier { basePositionMs }
        }

        return State.Builder()
            .setAvailableCommands(supportedCommands)
            .setPlayWhenReady(remote.isPlaying, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .setPlaybackState(Player.STATE_READY)
            .setPlaylist(listOf(mediaItemData))
            .setCurrentMediaItemIndex(0)
            .setContentPositionMs(positionSupplier)
            .setDeviceInfo(remoteDeviceInfo)
            .setDeviceVolume(deviceVolume)
            .setIsDeviceMuted(deviceVolume == 0)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        currentRemote = currentRemote?.copy(
            isPlaying = playWhenReady,
            updatedAt = System.currentTimeMillis()
        )
        lanConnectManager.sendCommand(if (playWhenReady) "play" else "pause")
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        currentRemote = currentRemote?.copy(
            isPlaying = false,
            updatedAt = System.currentTimeMillis()
        )
        lanConnectManager.sendCommand("pause")
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int
    ): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> {
                lanConnectManager.sendCommand("next")
            }
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
                lanConnectManager.sendCommand("prev")
            }
            else -> {
                val targetMs = if (positionMs == C.TIME_UNSET) 0L else positionMs.coerceAtLeast(0L)
                currentRemote = currentRemote?.copy(
                    positionMs = targetMs,
                    updatedAt = System.currentTimeMillis()
                )
                lanConnectManager.sendCommand("seek", positionMs = targetMs)
                invalidateState()
            }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int): ListenableFuture<*> {
        lanConnectManager.setRemoteVolume(deviceVolume / MAX_VOLUME.toFloat())
        return Futures.immediateVoidFuture()
    }

    override fun handleIncreaseDeviceVolume(flags: Int): ListenableFuture<*> {
        val current = currentRemote?.volume ?: 1f
        lanConnectManager.setRemoteVolume((current + VOLUME_STEP).coerceIn(0f, 1f))
        return Futures.immediateVoidFuture()
    }

    override fun handleDecreaseDeviceVolume(flags: Int): ListenableFuture<*> {
        val current = currentRemote?.volume ?: 1f
        lanConnectManager.setRemoteVolume((current - VOLUME_STEP).coerceIn(0f, 1f))
        return Futures.immediateVoidFuture()
    }

    override fun handleSetDeviceMuted(muted: Boolean, flags: Int): ListenableFuture<*> {
        lanConnectManager.setRemoteVolume(if (muted) 0f else 1f)
        return Futures.immediateVoidFuture()
    }

    private companion object {
        const val MAX_VOLUME = 100
        const val VOLUME_STEP = 0.05f
    }
}
