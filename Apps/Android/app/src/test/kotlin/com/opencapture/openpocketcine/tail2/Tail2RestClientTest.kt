package com.opencapture.openpocketcine.tail2

import kotlin.test.Test
import kotlin.test.assertEquals
import org.json.JSONObject

class Tail2RestClientTest {
    @Test
    fun monitorPrefersMatchingInterfaceSubStream() {
        val payload =
            JSONObject(
                """{
                    "wiredNetwork": {
                        "mainStreamUrl": "rtsp://10.0.0.2/stream1",
                        "subStreamUrl": "rtsp://10.0.0.2/stream2"
                    },
                    "wirelessNetwork": {
                        "mainStreamUrl": "rtsp://192.168.1.20/stream1",
                        "subStreamUrl": "rtsp://192.168.1.20/stream2"
                    }
                }""",
            )

        assertEquals(
            "rtsp://192.168.1.20/stream2",
            Tail2RestClient.selectMonitorRtspUrl(payload, "192.168.1.20"),
        )
    }

    @Test
    fun monitorFallsBackToStream2OnRestHost() {
        assertEquals(
            "rtsp://192.168.88.10/stream2",
            Tail2RestClient.selectMonitorRtspUrl(JSONObject(), "192.168.88.10"),
        )
    }
}
