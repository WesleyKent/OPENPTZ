package com.opencapture.openpocketcine.visca

import kotlin.math.ceil

/** Common Sony VISCA commands plus OBSBOT Tail 2's documented VISCA extensions. */
object ViscaCommands {
    fun panTilt(x: Float, y: Float): ByteArray {
        val nx = x.coerceIn(-1f, 1f)
        val ny = y.coerceIn(-1f, 1f)
        val panDirection = direction(nx, negative = 0x01, positive = 0x02)
        val tiltDirection = direction(ny, negative = 0x01, positive = 0x02)
        val panSpeed = speed(nx, 0x18)
        val tiltSpeed = speed(ny, 0x17)
        return bytes(0x81, 0x01, 0x06, 0x01, panSpeed, tiltSpeed, panDirection, tiltDirection, 0xFF)
    }

    fun panTiltStop(): ByteArray = bytes(0x81, 0x01, 0x06, 0x01, 0x01, 0x01, 0x03, 0x03, 0xFF)

    fun home(): ByteArray = bytes(0x81, 0x01, 0x06, 0x04, 0xFF)

    fun zoomStop(): ByteArray = bytes(0x81, 0x01, 0x04, 0x07, 0x00, 0xFF)

    fun zoomDrive(tele: Boolean, speed: Int): ByteArray {
        val nibble = (if (tele) 0x20 else 0x30) or speed.coerceIn(0, 7)
        return bytes(0x81, 0x01, 0x04, 0x07, nibble, 0xFF)
    }

    /** Standard VISCA absolute zoom range 0000h (wide) to 4000h (tele). */
    fun zoomAbsolute(normalized: Double): ByteArray = zoomCode((normalized.coerceIn(0.0, 1.0) * 0x4000).toInt())

    /** Tail 2 defines the four zoom nibbles as ratio × 1000. */
    fun tail2ZoomRatio(ratio: Double): ByteArray = zoomCode((ratio.coerceIn(1.0, 12.0) * 1_000.0).toInt())

    fun focusAuto(enabled: Boolean): ByteArray =
        bytes(0x81, 0x01, 0x04, 0x38, if (enabled) 0x02 else 0x03, 0xFF)

    fun focusOnePush(): ByteArray = bytes(0x81, 0x01, 0x04, 0x18, 0x01, 0xFF)

    fun exposureAuto(enabled: Boolean): ByteArray =
        bytes(0x81, 0x01, 0x04, 0x39, if (enabled) 0x00 else 0x03, 0xFF)

    fun record(enabled: Boolean): ByteArray =
        bytes(0x81, 0x01, 0x04, 0x66, if (enabled) 0x01 else 0x00, 0xFF)

    fun tail2Tracking(enabled: Boolean): ByteArray =
        bytes(0x81, 0x01, 0x8E, 0x00, if (enabled) 0x02 else 0x03, 0xFF)

    fun tail2TrackingSingle(): ByteArray = bytes(0x81, 0x01, 0x8E, 0x01, 0x00, 0xFF)

    private fun zoomCode(raw: Int): ByteArray {
        val value = raw.coerceIn(0, 0xFFFF)
        return bytes(
            0x81, 0x01, 0x04, 0x47,
            (value ushr 12) and 0x0F,
            (value ushr 8) and 0x0F,
            (value ushr 4) and 0x0F,
            value and 0x0F,
            0xFF,
        )
    }

    private fun direction(value: Float, negative: Int, positive: Int): Int =
        when {
            value < -DEAD_ZONE -> negative
            value > DEAD_ZONE -> positive
            else -> 0x03
        }

    private fun speed(value: Float, maximum: Int): Int =
        if (kotlin.math.abs(value) <= DEAD_ZONE) 0x01
        else ceil(kotlin.math.abs(value) * maximum).toInt().coerceIn(1, maximum)

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    private const val DEAD_ZONE = 0.04f
}
