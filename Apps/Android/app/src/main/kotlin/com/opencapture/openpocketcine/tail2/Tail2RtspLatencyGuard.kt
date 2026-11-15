package com.opencapture.openpocketcine.tail2

/** Actions used by the Tail 2 RTSP monitor to stay close to the live edge. */
internal enum class Tail2RtspLatencyAction {
    KEEP,
    NORMAL_SPEED,
    CATCH_UP,
    RESYNC,
}

/**
 * Adds hysteresis around Media3's small RTSP buffer and bounds a sustained
 * backlog. Short bursts are drained slightly faster; a large persistent queue
 * is discarded by reopening the live stream.
 */
internal class Tail2RtspLatencyGuard {
    private var catchingUp = false
    private var hardLimitSamples = 0
    private var lastResyncAtMs: Long? = null

    fun sample(
        bufferedDurationMs: Long,
        playing: Boolean,
        nowMs: Long,
    ): Tail2RtspLatencyAction {
        if (!playing) {
            hardLimitSamples = 0
            return stopCatchUp()
        }

        if (bufferedDurationMs >= HARD_RESYNC_BUFFER_MS) {
            hardLimitSamples += 1
        } else {
            hardLimitSamples = 0
        }

        val previousResync = lastResyncAtMs
        val outsideCooldown = previousResync == null || nowMs - previousResync >= RESYNC_COOLDOWN_MS
        if (hardLimitSamples >= HARD_RESYNC_SAMPLE_COUNT && outsideCooldown) {
            hardLimitSamples = 0
            catchingUp = false
            lastResyncAtMs = nowMs
            return Tail2RtspLatencyAction.RESYNC
        }

        if (!catchingUp && bufferedDurationMs >= CATCH_UP_START_BUFFER_MS) {
            catchingUp = true
            return Tail2RtspLatencyAction.CATCH_UP
        }
        if (catchingUp && bufferedDurationMs <= CATCH_UP_STOP_BUFFER_MS) {
            catchingUp = false
            return Tail2RtspLatencyAction.NORMAL_SPEED
        }
        return Tail2RtspLatencyAction.KEEP
    }

    fun reset(clearCooldown: Boolean) {
        catchingUp = false
        hardLimitSamples = 0
        if (clearCooldown) lastResyncAtMs = null
    }

    private fun stopCatchUp(): Tail2RtspLatencyAction {
        if (!catchingUp) return Tail2RtspLatencyAction.KEEP
        catchingUp = false
        return Tail2RtspLatencyAction.NORMAL_SPEED
    }

    internal companion object {
        const val CATCH_UP_START_BUFFER_MS = 220L
        const val CATCH_UP_STOP_BUFFER_MS = 90L
        const val HARD_RESYNC_BUFFER_MS = 900L
        const val HARD_RESYNC_SAMPLE_COUNT = 3
        const val RESYNC_COOLDOWN_MS = 15_000L
    }
}
