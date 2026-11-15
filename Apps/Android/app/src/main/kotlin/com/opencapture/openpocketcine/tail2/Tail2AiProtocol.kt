package com.opencapture.openpocketcine.tail2

import com.opencapture.openpocketcine.session.FocusTrackMode
import com.opencapture.openpocketcine.session.TrackingBox
import org.json.JSONObject

/** Tail 2 REST AI modes and normalized target-box rules from the official v1 API. */
object Tail2AiProtocol {
    const val NONE = "none"
    const val HUMAN_SINGLE = "humanTrackingSingleMode"
    const val HUMAN_GROUP = "humanTrackingGroupMode"
    const val OBJECT_NORMAL = "objectTrackingNormal"
    const val OBJECT_CLOSE_UP = "objectTrackingCloseUp"
    private const val MIN_TARGET_SIDE = 0.10

    fun requestForFocusTrack(raw: Int): JSONObject? =
        when (FocusTrackMode.fromRaw(raw)) {
            FocusTrackMode.DEFAULT -> modeRequest(NONE)
            FocusTrackMode.PRODUCT_SHOWCASE -> objectRequest(centerTarget(), closeUp = true)
            FocusTrackMode.SUBJECT_LOCK -> modeRequest(HUMAN_SINGLE)
            FocusTrackMode.REGISTERED_PRIORITY -> modeRequest(HUMAN_GROUP)
            null -> null
        }

    /**
     * Tail 2 firmware validates all five schema fields even for modes where the
     * coordinates are documented as ignored. Always send a complete body so
     * human/group/none do not fail with `no ymax`.
     */
    fun modeRequest(mode: String, box: TrackingBox = centerTarget()): JSONObject {
        val target = normalizedTarget(box)
        return JSONObject()
            .put("mode", mode)
            .put("xmin", target.minX)
            .put("ymin", target.minY)
            .put("xmax", target.maxX)
            .put("ymax", target.maxY)
    }

    fun objectRequest(box: TrackingBox, closeUp: Boolean = false): JSONObject {
        return modeRequest(if (closeUp) OBJECT_CLOSE_UP else OBJECT_NORMAL, box)
    }

    /** Small long-press/tap selections become a usable box instead of being silently rejected. */
    fun normalizedTarget(box: TrackingBox): TrackingBox {
        val width = box.width.coerceAtLeast(MIN_TARGET_SIDE).coerceAtMost(1.0)
        val height = box.height.coerceAtLeast(MIN_TARGET_SIDE).coerceAtMost(1.0)
        val x = (box.centerX - width / 2.0).coerceIn(0.0, 1.0 - width)
        val y = (box.centerY - height / 2.0).coerceIn(0.0, 1.0 - height)
        return TrackingBox(x, y, width, height)
    }

    fun focusTrackRaw(mode: String): Int =
        when (mode) {
            OBJECT_CLOSE_UP -> FocusTrackMode.PRODUCT_SHOWCASE.raw
            HUMAN_SINGLE, OBJECT_NORMAL -> FocusTrackMode.SUBJECT_LOCK.raw
            HUMAN_GROUP -> FocusTrackMode.REGISTERED_PRIORITY.raw
            else -> FocusTrackMode.DEFAULT.raw
        }

    fun isTracking(mode: String): Boolean = mode != NONE && mode.isNotBlank()

    fun isObjectTracking(mode: String): Boolean =
        mode == OBJECT_NORMAL || mode == OBJECT_CLOSE_UP

    fun acceptedModesFor(requestedMode: String): Set<String> = setOf(requestedMode)

    private fun centerTarget(): TrackingBox = TrackingBox.fromCenter(0.5, 0.5, 0.30, 0.40)
}
