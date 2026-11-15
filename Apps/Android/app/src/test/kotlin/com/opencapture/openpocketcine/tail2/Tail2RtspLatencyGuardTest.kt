package com.opencapture.openpocketcine.tail2

import kotlin.test.Test
import kotlin.test.assertEquals

class Tail2RtspLatencyGuardTest {
    @Test
    fun smallBufferKeepsNormalPlayback() {
        val guard = Tail2RtspLatencyGuard()

        assertEquals(Tail2RtspLatencyAction.KEEP, guard.sample(80, playing = true, nowMs = 1_000))
    }

    @Test
    fun catchUpUsesHysteresis() {
        val guard = Tail2RtspLatencyGuard()

        assertEquals(Tail2RtspLatencyAction.CATCH_UP, guard.sample(240, true, 1_000))
        assertEquals(Tail2RtspLatencyAction.KEEP, guard.sample(160, true, 1_500))
        assertEquals(Tail2RtspLatencyAction.NORMAL_SPEED, guard.sample(80, true, 2_000))
    }

    @Test
    fun sustainedLargeBufferRequestsResync() {
        val guard = Tail2RtspLatencyGuard()

        assertEquals(Tail2RtspLatencyAction.CATCH_UP, guard.sample(1_000, true, 1_000))
        assertEquals(Tail2RtspLatencyAction.KEEP, guard.sample(1_000, true, 1_500))
        assertEquals(Tail2RtspLatencyAction.RESYNC, guard.sample(1_000, true, 2_000))
    }

    @Test
    fun resyncCooldownPreventsRestartLoop() {
        val guard = Tail2RtspLatencyGuard()
        repeat(2) { guard.sample(1_000, true, 1_000L + it * 500L) }
        assertEquals(Tail2RtspLatencyAction.RESYNC, guard.sample(1_000, true, 2_000))

        repeat(3) { index ->
            assertEquals(
                if (index == 0) Tail2RtspLatencyAction.CATCH_UP else Tail2RtspLatencyAction.KEEP,
                guard.sample(1_000, true, 2_500L + index * 500L),
            )
        }
    }

    @Test
    fun pausingRestoresNormalSpeed() {
        val guard = Tail2RtspLatencyGuard()
        guard.sample(240, playing = true, nowMs = 1_000)

        assertEquals(Tail2RtspLatencyAction.NORMAL_SPEED, guard.sample(240, false, 1_500))
    }
}
