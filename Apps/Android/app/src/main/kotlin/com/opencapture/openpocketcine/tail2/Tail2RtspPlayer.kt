@file:androidx.media3.common.util.UnstableApi

package com.opencapture.openpocketcine.tail2

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector

/** Feeds Tail 2 RTSP into the same Surface used by the existing OES/Vulkan assist pipeline. */
class Tail2RtspPlayer(
    context: Context,
    private val onFirstFrame: () -> Unit,
    private val onVideoSize: (Int, Int) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    /*
     * Tail 2 advertises its AAC RTP stream with a 90 kHz clock on some firmware.
     * Media3 can expose that clock as the audio sample rate, causing the device's
     * AAC decoder to reject the track before the video renderer starts. OPENPTZ
     * uses this player only as a video source; live audio meters/control arrive
     * through the camera API, so do not select or instantiate an audio decoder.
     */
    private val trackSelector =
        DefaultTrackSelector(context.applicationContext).apply {
            parameters =
                buildUponParameters()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .build()
        }
    private val lowLatencyLoadControl =
        DefaultLoadControl.Builder()
            // This is a camera monitor, not VOD. Keeping Media3's multi-second
            // default buffer makes every PTZ move visibly arrive late.
            .setBufferDurationsMs(
                MIN_BUFFER_MS,
                MAX_BUFFER_MS,
                BUFFER_FOR_PLAYBACK_MS,
                BUFFER_AFTER_REBUFFER_MS,
            )
            .setBackBuffer(0, false)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
    private val player =
        ExoPlayer.Builder(context.applicationContext)
            .setTrackSelector(trackSelector)
            .setLoadControl(lowLatencyLoadControl)
            .build()
    @Volatile private var surface: Surface? = null
    @Volatile private var released = false
    @Volatile private var renderedFirstFrame = false
    private var currentUrl: String? = null
    private var firstFrameAtMs = 0L
    private val latencyGuard = Tail2RtspLatencyGuard()
    private val firstFrameTimeout =
        Runnable {
            if (!released && !renderedFirstFrame) {
                onError("RTSP connected but no video frame arrived within 12 seconds (RTP/UDP or TCP fallback)")
            }
        }
    private val latencyWatchdog =
        object : Runnable {
            override fun run() {
                if (released) return
                if (
                    renderedFirstFrame &&
                    SystemClock.elapsedRealtime() - firstFrameAtMs >= LATENCY_WATCHDOG_GRACE_MS
                ) {
                    val bufferedMs = player.totalBufferedDuration.coerceAtLeast(0L)
                    when (
                        latencyGuard.sample(
                            bufferedDurationMs = bufferedMs,
                            playing = player.isPlaying,
                            nowMs = SystemClock.elapsedRealtime(),
                        )
                    ) {
                        Tail2RtspLatencyAction.KEEP -> Unit
                        Tail2RtspLatencyAction.NORMAL_SPEED -> player.setPlaybackSpeed(1f)
                        Tail2RtspLatencyAction.CATCH_UP -> player.setPlaybackSpeed(CATCH_UP_SPEED)
                        Tail2RtspLatencyAction.RESYNC -> resyncToLiveEdge(bufferedMs)
                    }
                }
                main.postDelayed(this, LATENCY_WATCHDOG_INTERVAL_MS)
            }
        }

    init {
        player.addListener(
            object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    renderedFirstFrame = true
                    firstFrameAtMs = SystemClock.elapsedRealtime()
                    main.removeCallbacks(firstFrameTimeout)
                    onFirstFrame()
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (videoSize.width > 0 && videoSize.height > 0) {
                        onVideoSize(videoSize.width, videoSize.height)
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    main.removeCallbacks(firstFrameTimeout)
                    val detail = error.message?.takeIf(String::isNotBlank) ?: "RTSP preview failed"
                    onError("${error.errorCodeName}: $detail")
                }
            },
        )
    }

    fun open(url: String) {
        onPlayerThread {
            currentUrl = url
            latencyGuard.reset(clearCooldown = true)
            player.setPlaybackSpeed(1f)
            renderedFirstFrame = false
            prepare(url)
            main.removeCallbacks(latencyWatchdog)
            main.postDelayed(latencyWatchdog, LATENCY_WATCHDOG_INTERVAL_MS)
        }
    }

    private fun prepare(url: String) {
        val source =
            RtspMediaSource.Factory()
                // Tail 2 is on the same LAN, so UDP is the correct monitor
                // transport: when a phone/GPU misses its deadline, obsolete
                // datagrams can be discarded instead of accumulating in TCP's
                // reliable byte stream. Media3 automatically retries with
                // interleaved RTP/TCP if startup UDP packets never arrive.
                .setTimeoutMs(RTP_TIMEOUT_MS)
                .createMediaSource(MediaItem.fromUri(url))
        player.setMediaSource(source)
        player.setVideoSurface(surface)
        player.playWhenReady = true
        player.prepare()
        main.removeCallbacks(firstFrameTimeout)
        main.postDelayed(firstFrameTimeout, FIRST_FRAME_TIMEOUT_MS)
    }

    private fun resyncToLiveEdge(bufferedMs: Long) {
        val url = currentUrl ?: return
        Log.w(TAG, "RTSP backlog ${bufferedMs}ms; reopening at the live edge")
        player.setPlaybackSpeed(1f)
        renderedFirstFrame = false
        firstFrameAtMs = 0L
        player.stop()
        prepare(url)
    }

    fun attachSurface(next: Surface?) {
        surface = next
        // LiveVulkanSession recreates its ImageReader on an image worker. Media3
        // requires every ExoPlayer call, including a Surface swap, on its app looper.
        onPlayerThread {
            player.setVideoSurface(next)
            if (next != null && !renderedFirstFrame) {
                main.removeCallbacks(firstFrameTimeout)
                main.postDelayed(firstFrameTimeout, FIRST_FRAME_TIMEOUT_MS)
            }
        }
    }

    fun pause() = onPlayerThread { player.pause() }

    fun resume() {
        onPlayerThread {
            player.playWhenReady = true
            player.play()
        }
    }

    fun release() {
        if (released) return
        released = true
        main.removeCallbacks(firstFrameTimeout)
        main.removeCallbacks(latencyWatchdog)
        currentUrl = null
        surface = null
        if (Looper.myLooper() == main.looper) {
            player.clearVideoSurface()
            player.release()
        } else {
            main.post {
                player.clearVideoSurface()
                player.release()
            }
        }
    }

    private inline fun onPlayerThread(crossinline block: () -> Unit) {
        if (released) return
        if (Looper.myLooper() == main.looper) {
            block()
        } else {
            main.post { if (!released) block() }
        }
    }

    private companion object {
        const val TAG = "Tail2RtspPlayer"
        const val FIRST_FRAME_TIMEOUT_MS = 12_000L
        const val RTP_TIMEOUT_MS = 2_500L
        const val LATENCY_WATCHDOG_INTERVAL_MS = 500L
        const val LATENCY_WATCHDOG_GRACE_MS = 3_000L
        const val CATCH_UP_SPEED = 1.08f
        const val MIN_BUFFER_MS = 50
        const val MAX_BUFFER_MS = 120
        const val BUFFER_FOR_PLAYBACK_MS = 0
        const val BUFFER_AFTER_REBUFFER_MS = 25
    }
}
