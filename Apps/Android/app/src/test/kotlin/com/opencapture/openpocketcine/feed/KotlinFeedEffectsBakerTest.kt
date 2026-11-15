package com.opencapture.openpocketcine.feed

import com.opencapture.openpocketcine.assists.FalseColorScale
import com.opencapture.openpocketcine.session.CameraCommands
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KotlinFeedEffectsBakerTest {
    @Test
    fun `contrast fallback produces a non-identity cube`() {
        val cube = FeedEffectsCube.fromPacked(
            assertNotNull(KotlinFeedEffectsBaker.creative("Contrast", 0.0, CameraCommands.COLOR_NORMAL)),
        )
        assertNotNull(cube)
        val middle = cube.map(0.25f, 0.5f, 0.75f)
        assertNotEquals(0.25f, middle.first)
        assertNotEquals(0.75f, middle.third)
    }

    @Test
    fun `false colour fallback supplies paint and opaque IRE weights`() {
        val paint = FeedEffectsCube.fromPacked(
            KotlinFeedEffectsBaker.falseColor(true, FalseColorScale.IRE, CameraCommands.COLOR_NORMAL, 100),
        )
        val weight = FeedEffectsCube.fromPacked(
            KotlinFeedEffectsBaker.falseColor(false, FalseColorScale.IRE, CameraCommands.COLOR_NORMAL, 100),
        )
        assertNotNull(paint)
        assertNotNull(weight)
        val falseColorBand = paint.map(0.56f, 0.56f, 0.56f)
        assertTrue(falseColorBand.first != falseColorBand.second || falseColorBand.second != falseColorBand.third)
        assertTrue(weight.map(0.5f, 0.5f, 0.5f).first > 0.99f)
    }

    @Test
    fun `cube fallback parses red-fastest 3d lattice`() {
        val text = buildString {
            appendLine("TITLE \"identity\"")
            appendLine("LUT_3D_SIZE 2")
            for (blue in 0..1) for (green in 0..1) for (red in 0..1) {
                appendLine("$red $green $blue")
            }
        }
        val cube = FeedEffectsCube.fromPacked(
            assertNotNull(KotlinFeedEffectsBaker.imported(text.encodeToByteArray(), 0.0, CameraCommands.COLOR_NORMAL)),
        )
        assertNotNull(cube)
        val mapped = cube.map(1f, 0f, 1f)
        assertTrue(mapped.first > 0.99f)
        assertTrue(mapped.second < 0.01f)
        assertTrue(mapped.third > 0.99f)
    }
}
