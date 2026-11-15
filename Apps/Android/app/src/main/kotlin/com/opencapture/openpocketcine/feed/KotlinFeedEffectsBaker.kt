package com.opencapture.openpocketcine.feed

import com.opencapture.openpocketcine.assists.FalseColorBands
import com.opencapture.openpocketcine.assists.FalseColorScale
import kotlin.math.pow

/**
 * Pure Kotlin monitor-cube fallback for OPENPTZ builds that do not ship the
 * legacy Pocket Swift/DUML runtime. Tail 2 must keep LUT and false colour
 * independent from that camera-specific library.
 */
internal object KotlinFeedEffectsBaker {
    private const val CREATIVE_SIZE = 17
    private const val FALSE_COLOR_SIZE = 33

    fun creative(
        name: String,
        exposureStops: Double,
        colorMode: Int,
    ): ByteArray? {
        val normalized = name.lowercase()
        if (normalized !in setOf("mono", "contrast", "warm", "cool")) return null
        val transfer = MonitorTransfer.fromColorMode(colorMode)
        val gain = 2.0.pow(exposureStops)
        return packed(CREATIVE_SIZE) { red, green, blue ->
            fun exposed(value: Double): Double =
                LiveColorScience.encode(LiveColorScience.linearize(value, transfer) * gain, transfer)
            val r = exposed(red)
            val g = exposed(green)
            val b = exposed(blue)
            when (normalized) {
                "mono" -> {
                    val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
                    Triple(y, y, y)
                }
                "contrast" -> Triple(contrast(r), contrast(g), contrast(b))
                "warm" -> Triple((r * 1.08 + 0.04).coerceIn(0.0, 1.0), g, (b * 0.88 - 0.02).coerceIn(0.0, 1.0))
                else -> Triple((r * 0.90 - 0.02).coerceIn(0.0, 1.0), g, (b * 1.10 + 0.03).coerceIn(0.0, 1.0))
            }
        }
    }

    /** Minimal standards-compatible 3D `.cube` reader for official/custom LUTs. */
    fun imported(
        utf8: ByteArray,
        exposureStops: Double,
        colorMode: Int,
    ): ByteArray? {
        val text = utf8.toString(Charsets.UTF_8)
        var size = 0
        val values = ArrayList<Triple<Double, Double, Double>>()
        for (rawLine in text.lineSequence()) {
            val line = rawLine.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val fields = line.split(Regex("\\s+"))
            when (fields.first().uppercase()) {
                "TITLE", "DOMAIN_MIN", "DOMAIN_MAX", "LUT_1D_SIZE" -> continue
                "LUT_3D_SIZE" -> {
                    size = fields.getOrNull(1)?.toIntOrNull() ?: return null
                    if (size !in 2..64) return null
                }
                else -> {
                    if (fields.size < 3) continue
                    val red = fields[0].toDoubleOrNull() ?: return null
                    val green = fields[1].toDoubleOrNull() ?: return null
                    val blue = fields[2].toDoubleOrNull() ?: return null
                    values += Triple(red, green, blue)
                }
            }
        }
        if (size !in 2..64 || values.size != size * size * size) return null
        val transfer = MonitorTransfer.fromColorMode(colorMode)
        val gain = 2.0.pow(exposureStops)
        val bytes = ByteArray(size * size * size * 4)
        // .cube rows and CubeLUT are red-fastest, then green, then blue.
        for (blue in 0 until size) {
            for (green in 0 until size) {
                for (red in 0 until size) {
                    val source = values[red + green * size + blue * size * size]
                    fun exposed(value: Double): Double =
                        LiveColorScience.encode(
                            LiveColorScience.linearize(value, transfer) * gain,
                            transfer,
                        )
                    val destination = (green * size * size + blue * size + red) * 4
                    bytes[destination] = quantize(exposed(source.first))
                    bytes[destination + 1] = quantize(exposed(source.second))
                    bytes[destination + 2] = quantize(exposed(source.third))
                    bytes[destination + 3] = 0xFF.toByte()
                }
            }
        }
        return bytes
    }

    fun falseColor(
        paint: Boolean,
        scale: FalseColorScale,
        colorMode: Int,
        iso: Int,
    ): ByteArray {
        val transfer = MonitorTransfer.fromColorMode(colorMode)
        val bands = FalseColorBands.bands(scale, transfer)
        val weights = LiveColorScience.lumaWeights(transfer)
        return packed(FALSE_COLOR_SIZE) { red, green, blue ->
            val encodedLuma = red * weights.first + green * weights.second + blue * weights.third
            val monitor = ScopeDisplayScale.monitorPercent(encodedLuma, transfer, iso)
            val value =
                if (scale == FalseColorScale.STOPS) {
                    LiveColorScience.stops(encodedLuma, transfer)
                } else {
                    monitor
                }
            val band = bands.firstOrNull { it.contains(value) }
            val weight = if (scale == FalseColorScale.LIMITS) if (band == null) 0.0 else 1.0 else 1.0
            if (!paint) {
                Triple(weight, weight, weight)
            } else if (band != null) {
                if (scale == FalseColorScale.STOPS) {
                    val gray = (monitor / 100.0).coerceIn(0.0, 1.0)
                    Triple(
                        band.red * 0.6 + gray * 0.4,
                        band.green * 0.6 + gray * 0.4,
                        band.blue * 0.6 + gray * 0.4,
                    )
                } else {
                    Triple(band.red, band.green, band.blue)
                }
            } else {
                val gray = (monitor / 100.0).coerceIn(0.0, 1.0)
                Triple(gray, gray, gray)
            }
        }
    }

    private fun contrast(value: Double): Double =
        (0.5 + 1.35 * (value.coerceIn(0.0, 1.0) - 0.5)).coerceIn(0.0, 1.0)

    private inline fun packed(
        size: Int,
        map: (red: Double, green: Double, blue: Double) -> Triple<Double, Double, Double>,
    ): ByteArray {
        val bytes = ByteArray(size * size * size * 4)
        val denominator = (size - 1).toDouble()
        for (green in 0 until size) {
            for (blue in 0 until size) {
                for (red in 0 until size) {
                    val color = map(red / denominator, green / denominator, blue / denominator)
                    val offset = (green * size * size + blue * size + red) * 4
                    bytes[offset] = quantize(color.first)
                    bytes[offset + 1] = quantize(color.second)
                    bytes[offset + 2] = quantize(color.third)
                    bytes[offset + 3] = 0xFF.toByte()
                }
            }
        }
        return bytes
    }

    private fun quantize(value: Double): Byte =
        (value.coerceIn(0.0, 1.0) * 255.0 + 0.5).toInt().toByte()
}
