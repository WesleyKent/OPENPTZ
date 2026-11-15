package com.opencapture.openpocketcine.uvc

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import android.view.Surface
import com.serenegiant.usb.Size
import com.serenegiant.usb.USBMonitor
import com.serenegiant.usb.UVCParam
import com.serenegiant.usb.UVCCamera
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class UvcDeviceInfo(
    val deviceName: String,
    val displayName: String,
    val vendorId: Int,
    val productId: Int,
) {
    val usbId: String get() = "%04X:%04X".format(vendorId, productId)
    val label: String get() = "$displayName · $usbId"
}

data class UvcStreamInfo(
    val device: UvcDeviceInfo,
    val width: Int,
    val height: Int,
    val format: String,
)

/**
 * Brand-independent USB Video Class input. Device matching is based on the
 * interface class (0x0E), which also catches composite HDMI/SDI capture cards.
 */
class UvcVideoInput(
    context: Context,
    private val onOpened: (UvcStreamInfo) -> Unit,
    private val onFailure: (String) -> Unit,
    private val onDetached: () -> Unit,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val lock = Any()
    private val _devices = MutableStateFlow<List<UvcDeviceInfo>>(emptyList())
    val devices: StateFlow<List<UvcDeviceInfo>> = _devices.asStateFlow()

    private var selectedDeviceName: String? = null
    private var camera: UVCCamera? = null
    private var surface: Surface? = null
    private var previewStarted = false
    private var closed = false

    private val monitor =
        USBMonitor(appContext, object : USBMonitor.OnDeviceConnectListener {
            override fun onAttach(device: UsbDevice) {
                refreshDevices()
            }

            override fun onDetach(device: UsbDevice) {
                val wasSelected = device.deviceName == selectedDeviceName
                refreshDevices()
                if (wasSelected) {
                    releaseCamera(clearSelection = true)
                    onFailure("USB video device was disconnected")
                    onDetached()
                }
            }

            override fun onDeviceOpen(
                device: UsbDevice,
                ctrlBlock: USBMonitor.UsbControlBlock,
                createNew: Boolean,
            ) {
                if (device.deviceName != selectedDeviceName) return
                runCatching { openGrantedDevice(device, ctrlBlock) }
                    .onFailure { error ->
                        releaseCamera(clearSelection = false)
                        onFailure("Could not open ${deviceLabel(device)}: ${error.message ?: error.javaClass.simpleName}")
                    }
            }

            override fun onDeviceClose(device: UsbDevice, ctrlBlock: USBMonitor.UsbControlBlock) {
                if (device.deviceName != selectedDeviceName) return
                releaseCamera(clearSelection = false)
                onFailure("USB video stream stopped")
                onDetached()
            }

            override fun onCancel(device: UsbDevice) {
                if (device.deviceName == selectedDeviceName) {
                    onFailure("USB permission was not granted for ${deviceLabel(device)}")
                }
            }
        })

    init {
        monitor.register()
        refreshDevices()
    }

    fun refreshDevices() {
        if (closed) return
        _devices.value =
            usbManager.deviceList.values
                .asSequence()
                .filter(::isUvcDevice)
                .map(::deviceInfo)
                .sortedWith(compareBy<UvcDeviceInfo> { it.displayName.lowercase() }.thenBy { it.usbId })
                .toList()
    }

    /** Requests Android's per-device USB permission; opening continues in onDeviceOpen. */
    fun open(deviceName: String) {
        check(!closed) { "USB monitor is closed" }
        releaseCamera(clearSelection = false)
        refreshDevices()
        val device = usbManager.deviceList[deviceName]
            ?: throw IllegalArgumentException("The selected USB video device is no longer connected")
        require(isUvcDevice(device)) { "The selected USB device does not expose a UVC interface" }
        selectedDeviceName = device.deviceName
        monitor.requestPermission(device)
    }

    fun attachSurface(next: Surface?) {
        synchronized(lock) {
            if (surface === next) return
            val active = camera
            if (previewStarted) {
                runCatching { active?.stopPreview() }
                previewStarted = false
            }
            surface = next
            if (active != null && next != null && next.isValid) {
                active.setPreviewDisplay(next)
                active.startPreview()
                previewStarted = true
            }
        }
    }

    fun pause() {
        synchronized(lock) {
            if (previewStarted) {
                runCatching { camera?.stopPreview() }
                previewStarted = false
            }
        }
    }

    fun resume() {
        synchronized(lock) {
            val active = camera ?: return
            val target = surface ?: return
            if (!previewStarted && target.isValid) {
                active.setPreviewDisplay(target)
                active.startPreview()
                previewStarted = true
            }
        }
    }

    fun release() {
        releaseCamera(clearSelection = true)
    }

    override fun close() {
        if (closed) return
        closed = true
        releaseCamera(clearSelection = true)
        monitor.destroy()
        _devices.value = emptyList()
    }

    private fun openGrantedDevice(device: UsbDevice, ctrlBlock: USBMonitor.UsbControlBlock) {
        val opened = UVCCamera(UVCParam())
        try {
            val result = opened.open(ctrlBlock)
            check(result == 0) { "UVC device open failed (error $result)" }
            val negotiated = negotiate(opened)
            synchronized(lock) {
                camera?.destroy()
                camera = opened
                val target = surface
                if (target != null && target.isValid) {
                    opened.setPreviewDisplay(target)
                    opened.startPreview()
                    previewStarted = true
                }
            }
            onOpened(
                UvcStreamInfo(
                    device = deviceInfo(device),
                    width = negotiated.width,
                    height = negotiated.height,
                    format = if (negotiated.frameType == UVCCamera.UVC_VS_FRAME_MJPEG) "MJPEG" else "YUYV",
                ),
            )
        } catch (error: Throwable) {
            opened.destroy()
            throw error
        }
    }

    private fun negotiate(opened: UVCCamera): NegotiatedMode {
        val advertised =
            opened.supportedSizeList
                .orEmpty()
                .filter { it.type == UVC_FRAME_UNCOMPRESSED || it.type == UVC_FRAME_MJPEG }
                .distinctBy { Triple(it.type, it.width, it.height) }
        val modes =
            if (advertised.isEmpty()) fallbackModes()
            else advertised.sortedWith(modeComparator()).map(::toMode)

        var lastError: Throwable? = null
        for (mode in modes) {
            val advertisedFps = mode.fpsList.filter { it > 0 }.sortedDescending()
            val fpsCandidates = (advertisedFps + listOf(mode.preferredFps, 30)).distinct()
            for (fps in fpsCandidates) {
                try {
                    opened.setPreviewSize(mode.width, mode.height, mode.frameType, fps)
                    Log.i(TAG, "UVC negotiated ${mode.width}x${mode.height} ${mode.formatLabel} ${fps}fps")
                    return mode.copy(preferredFps = fps)
                } catch (error: IllegalArgumentException) {
                    lastError = error
                }
            }
        }
        throw IllegalArgumentException("No compatible MJPEG or YUYV preview mode", lastError)
    }

    private fun modeComparator(): Comparator<Size> =
        compareBy<Size> { modeTier(it) }
            .thenByDescending { it.width.toLong() * it.height.toLong() }

    private fun modeTier(size: Size): Int {
        val mjpeg = size.type == UVC_FRAME_MJPEG
        val area = size.width.toLong() * size.height.toLong()
        val within1080 = area <= 1920L * 1080L
        return when {
            mjpeg && size.width == 1920 && size.height == 1080 -> 0
            mjpeg && size.width == 1280 && size.height == 720 -> 1
            mjpeg && within1080 -> 2
            !mjpeg && size.width == 1920 && size.height == 1080 -> 3
            !mjpeg && size.width == 1280 && size.height == 720 -> 4
            !mjpeg && within1080 -> 5
            mjpeg -> 6
            else -> 7
        }
    }

    private fun toMode(size: Size): NegotiatedMode =
        NegotiatedMode(
            size.width,
            size.height,
            size.type,
            size.fps.takeIf { it > 0 } ?: 30,
            size.fpsList.orEmpty(),
        )

    private fun fallbackModes(): List<NegotiatedMode> =
        listOf(1920 to 1080, 1280 to 720, 640 to 480).flatMap { (width, height) ->
            listOf(
                NegotiatedMode(width, height, UVC_FRAME_MJPEG, 30, listOf(30)),
                NegotiatedMode(width, height, UVC_FRAME_UNCOMPRESSED, 30, listOf(30)),
            )
        }

    private fun releaseCamera(clearSelection: Boolean) {
        synchronized(lock) {
            previewStarted = false
            camera?.let { active ->
                runCatching { active.stopPreview() }
                runCatching { active.destroy() }
            }
            camera = null
            if (clearSelection) selectedDeviceName = null
        }
    }

    private fun deviceInfo(device: UsbDevice): UvcDeviceInfo =
        UvcDeviceInfo(
            deviceName = device.deviceName,
            displayName = deviceLabel(device),
            vendorId = device.vendorId,
            productId = device.productId,
        )

    private fun deviceLabel(device: UsbDevice): String =
        runCatching { device.productName?.takeIf(String::isNotBlank) }.getOrNull()
            ?: runCatching { device.manufacturerName?.takeIf(String::isNotBlank) }.getOrNull()
            ?: "USB Video"

    private fun isUvcDevice(device: UsbDevice): Boolean {
        if (device.deviceClass == UsbConstants.USB_CLASS_VIDEO) return true
        return (0 until device.interfaceCount).any { index ->
            device.getInterface(index).interfaceClass == UsbConstants.USB_CLASS_VIDEO
        }
    }

    private data class NegotiatedMode(
        val width: Int,
        val height: Int,
        val frameType: Int,
        val preferredFps: Int,
        val fpsList: List<Int>,
    ) {
        val formatLabel: String get() = if (frameType == UVC_FRAME_MJPEG) "MJPEG" else "YUYV"
    }

    companion object {
        private const val TAG = "OpenPtzUvc"
        private const val UVC_FRAME_UNCOMPRESSED = UVCCamera.UVC_VS_FRAME_UNCOMPRESSED
        private const val UVC_FRAME_MJPEG = UVCCamera.UVC_VS_FRAME_MJPEG
    }
}
