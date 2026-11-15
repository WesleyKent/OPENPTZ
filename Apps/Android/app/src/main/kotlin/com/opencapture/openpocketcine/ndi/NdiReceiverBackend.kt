package com.opencapture.openpocketcine.ndi

import android.os.Handler
import android.os.Looper
import com.opencapture.openpocketcine.feed.OpcVulkan

/**
 * Lifecycle seam around the locally supplied NDI Android runtime. The NDI SDK
 * decodes HX/HX3 and native code copies RGBX frames into an AHardwareBuffer
 * consumed by the existing Vulkan exposure-assist renderer.
 */
interface NdiReceiverBackend {
    val isAvailable: Boolean
    val unavailableReason: String?
    val isRequested: Boolean

    fun connect(
        sourceHint: String,
        onFirstFrame: () -> Unit,
        onVideoSize: (Int, Int) -> Unit,
        onError: (String) -> Unit,
    )

    fun attachRenderer(handle: Long, onFrameCount: (Int) -> Unit)
    fun detachRenderer(handle: Long)
    fun disconnect()
}

object AndroidNdiReceiver : NdiReceiverBackend {
    private val main = Handler(Looper.getMainLooper())
    private var sourceHint = ""
    private var renderer = 0L
    private var firstFrameSent = false
    private var lastWidth = 0
    private var lastHeight = 0
    private var lastError = ""
    private var onFirstFrame: () -> Unit = {}
    private var onVideoSize: (Int, Int) -> Unit = { _, _ -> }
    private var onError: (String) -> Unit = {}
    private var onFrameCount: (Int) -> Unit = {}

    override val isAvailable: Boolean
        get() =
            OpcVulkan.isAvailable &&
                runCatching { OpcVulkan.nativeNdiIsAvailable() }.getOrDefault(false)

    override val unavailableReason: String?
        get() = if (isAvailable) null else "This APK was built without the NDI Android runtime."

    /**
     * The public NDI Android SDK can receive SpeedHQ, but NDI documents that it
     * does not decode HX/HX3 on Android. HX compressed pass-through is an
     * Advanced SDK feature and still requires an app-owned MediaCodec pipeline.
     */
    const val canDecodeTail2Hx: Boolean = false
    const val tail2HxUnavailableReason: String =
        "The standard NDI Android SDK cannot decode Tail 2 HX3. " +
            "OPENPTZ will use the low-latency RTSP monitor path instead."

    @Volatile
    override var isRequested: Boolean = false
        private set

    override fun connect(
        sourceHint: String,
        onFirstFrame: () -> Unit,
        onVideoSize: (Int, Int) -> Unit,
        onError: (String) -> Unit,
    ) {
        check(isAvailable) { checkNotNull(unavailableReason) }
        this.sourceHint = sourceHint.trim()
        this.onFirstFrame = onFirstFrame
        this.onVideoSize = onVideoSize
        this.onError = onError
        firstFrameSent = false
        lastWidth = 0
        lastHeight = 0
        lastError = ""
        isRequested = true
        if (renderer != 0L) startNative(renderer)
    }

    override fun attachRenderer(handle: Long, onFrameCount: (Int) -> Unit) {
        if (!isRequested || handle == 0L) return
        if (renderer != 0L && renderer != handle) OpcVulkan.nativeNdiStop(renderer)
        renderer = handle
        this.onFrameCount = onFrameCount
        startNative(handle)
    }

    override fun detachRenderer(handle: Long) {
        if (renderer != handle) return
        main.removeCallbacks(poll)
        OpcVulkan.nativeNdiStop(handle)
        renderer = 0L
        onFrameCount = {}
    }

    override fun disconnect() {
        main.removeCallbacks(poll)
        val active = renderer
        if (active != 0L) OpcVulkan.nativeNdiStop(active)
        renderer = 0L
        sourceHint = ""
        isRequested = false
        firstFrameSent = false
        onFirstFrame = {}
        onVideoSize = { _, _ -> }
        onError = {}
        onFrameCount = {}
    }

    private fun startNative(handle: Long) {
        main.removeCallbacks(poll)
        if (!OpcVulkan.nativeNdiStart(handle, sourceHint)) {
            onError("NDI receiver could not start")
            return
        }
        main.post(poll)
    }

    private val poll =
        object : Runnable {
            override fun run() {
                val active = renderer
                if (!isRequested || active == 0L) return
                val state = runCatching { OpcVulkan.nativeNdiState(active) }.getOrNull()
                if (state == null || state.size < 7) {
                    onError("NDI receiver state is unavailable")
                    return
                }
                val width = state[2]
                val height = state[3]
                val frames = state[4]
                if (width > 0 && height > 0 && (width != lastWidth || height != lastHeight)) {
                    lastWidth = width
                    lastHeight = height
                    onVideoSize(width, height)
                }
                onFrameCount(frames)
                if (frames > 0 && !firstFrameSent) {
                    firstFrameSent = true
                    onFirstFrame()
                }
                if (state[5] != 0) {
                    val message = OpcVulkan.nativeNdiError(active).ifBlank { "NDI receiver failed" }
                    if (message != lastError) {
                        lastError = message
                        onError(message)
                    }
                }
                if (state[0] != 0) main.postDelayed(this, POLL_MS)
            }
        }

    private const val POLL_MS = 100L
}
