package com.opencapture.openpocketcine.tail2

import kotlin.test.Test
import kotlin.test.assertEquals

class Tail2PreviewPolicyTest {
    @Test
    fun tail2HxFallsBackWhenAndroidDecoderIsUnavailable() {
        assertEquals(
            Tail2PreviewMode.RTSP,
            Tail2PreviewPolicy.effectiveMode(Tail2PreviewMode.NDI, canDecodeHx = false),
        )
        assertEquals(
            Tail2PreviewMode.NDI,
            Tail2PreviewPolicy.effectiveMode(Tail2PreviewMode.NDI, canDecodeHx = true),
        )
        assertEquals(
            Tail2PreviewMode.UVC,
            Tail2PreviewPolicy.effectiveMode(Tail2PreviewMode.UVC, canDecodeHx = false),
        )
    }
}
