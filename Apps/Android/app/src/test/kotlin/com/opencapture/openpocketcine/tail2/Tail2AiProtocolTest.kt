package com.opencapture.openpocketcine.tail2

import com.opencapture.openpocketcine.session.FocusTrackMode
import com.opencapture.openpocketcine.session.TrackingBox
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Tail2AiProtocolTest {
    @Test
    fun smallSelectionExpandsAndStaysInsideFrame() {
        val request = Tail2AiProtocol.objectRequest(TrackingBox(0.98, 0.98, 0.0, 0.0))
        assertEquals(Tail2AiProtocol.OBJECT_NORMAL, request.getString("mode"))
        assertTrue(request.getDouble("xmin") >= 0.0)
        assertTrue(request.getDouble("ymin") >= 0.0)
        assertTrue(request.getDouble("xmax") <= 1.0)
        assertTrue(request.getDouble("ymax") <= 1.0)
        assertEquals(0.10, request.getDouble("xmax") - request.getDouble("xmin"), 1e-9)
    }

    @Test
    fun focusModesUseTail2OfficialAiModes() {
        assertEquals(
            Tail2AiProtocol.HUMAN_SINGLE,
            Tail2AiProtocol.requestForFocusTrack(FocusTrackMode.SUBJECT_LOCK.raw)?.getString("mode"),
        )
        assertEquals(
            Tail2AiProtocol.HUMAN_GROUP,
            Tail2AiProtocol.requestForFocusTrack(FocusTrackMode.REGISTERED_PRIORITY.raw)?.getString("mode"),
        )
        assertEquals(
            Tail2AiProtocol.OBJECT_CLOSE_UP,
            Tail2AiProtocol.requestForFocusTrack(FocusTrackMode.PRODUCT_SHOWCASE.raw)?.getString("mode"),
        )
    }

    @Test
    fun allAiModesIncludeFirmwareRequiredCoordinates() {
        listOf(
            FocusTrackMode.DEFAULT,
            FocusTrackMode.SUBJECT_LOCK,
            FocusTrackMode.REGISTERED_PRIORITY,
        ).forEach { mode ->
            val request = checkNotNull(Tail2AiProtocol.requestForFocusTrack(mode.raw))
            listOf("xmin", "ymin", "xmax", "ymax").forEach { field ->
                assertTrue(request.has(field), "$mode must include $field")
            }
        }
    }
}
