package com.opencapture.openpocketcine.tail2

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.opencapture.openpocketcine.AppModel
import com.opencapture.openpocketcine.LiveType
import com.opencapture.openpocketcine.LiveTypeDesign
import com.opencapture.openpocketcine.core.ConnectionPhase
import com.opencapture.openpocketcine.ndi.AndroidNdiReceiver
import com.opencapture.openpocketcine.pairing.StartupColors
import com.opencapture.openpocketcine.pairing.startupBackdrop
import com.opencapture.openpocketcine.pairing.startupCard
import com.opencapture.openpocketcine.uvc.UvcDeviceInfo
import com.opencapture.openpocketcine.visca.ViscaOverIpClient

@Composable
fun OpenPtzConnectionExperience(model: AppModel) {
    val context = LocalContext.current
    val phase by model.session.phaseFlow.collectAsState()
    val failure by model.session.failure.collectAsState()
    val uvcDevices by model.session.uvcDevices.collectAsState()
    var source by remember { mutableStateOf(model.connectionSource) }
    var host by remember { mutableStateOf(model.tail2Host) }
    var mode by remember { mutableStateOf(model.previewMode) }
    var selectedUvc by remember { mutableStateOf<String?>(null) }
    var viscaEnabled by remember { mutableStateOf(model.viscaHost.isNotBlank()) }
    var viscaHost by remember { mutableStateOf(model.viscaHost) }
    var viscaPort by remember { mutableStateOf(model.viscaPort.toString()) }
    var permissionFailure by remember { mutableStateOf<String?>(null) }
    val busy = phase == ConnectionPhase.OPENING_DATALINK
    val needsUvc = source == OpenPtzConnectionSource.USB_VIDEO || mode == Tail2PreviewMode.UVC

    LaunchedEffect(Unit) { model.session.refreshUvcDevices() }
    LaunchedEffect(source) {
        // A generic USB monitor needs a concrete device. Tail 2 UVC may not
        // enumerate until REST switches the camera out of MTP, so do not
        // silently preselect an unrelated capture card in that profile.
        selectedUvc =
            if (source == OpenPtzConnectionSource.USB_VIDEO) uvcDevices.firstOrNull()?.deviceName
            else null
    }
    LaunchedEffect(uvcDevices, source) {
        val selectedStillPresent = selectedUvc in uvcDevices.map { it.deviceName }
        if (!selectedStillPresent) {
            selectedUvc =
                if (source == OpenPtzConnectionSource.USB_VIDEO) uvcDevices.firstOrNull()?.deviceName
                else null
        }
    }

    val connectNow by rememberUpdatedState(newValue = {
        permissionFailure = null
        if (source == OpenPtzConnectionSource.TAIL2) {
            model.connectTail2(host, mode, selectedUvc)
        } else {
            model.connectUvc(
                deviceName = checkNotNull(selectedUvc),
                controlHost = viscaHost.takeIf { viscaEnabled },
                controlPort = viscaPort.toIntOrNull() ?: ViscaOverIpClient.DEFAULT_PORT,
            )
        }
    })
    val cameraPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) connectNow()
            else permissionFailure = "Camera permission is required by Android before a UVC device can be opened."
        }
    val onConnect = {
        if (
            needsUvc &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
        ) {
            cameraPermission.launch(Manifest.permission.CAMERA)
        } else {
            connectNow()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().startupBackdrop().padding(24.dp)) {
        val wide = maxWidth >= 700.dp
        if (wide) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                IntroPanel(Modifier.weight(0.35f).fillMaxHeight())
                ConnectPanel(
                    source = source,
                    onSource = { source = it },
                    host = host,
                    onHost = { host = it },
                    mode = mode,
                    onMode = { mode = it },
                    devices = uvcDevices,
                    selectedUvc = selectedUvc,
                    onSelectedUvc = { selectedUvc = it },
                    viscaEnabled = viscaEnabled,
                    onViscaEnabled = { viscaEnabled = it },
                    viscaHost = viscaHost,
                    onViscaHost = { viscaHost = it },
                    viscaPort = viscaPort,
                    onViscaPort = { viscaPort = it.filter(Char::isDigit).take(5) },
                    phase = phase,
                    failure = permissionFailure ?: failure,
                    busy = busy,
                    onRefreshUsb = model.session::refreshUvcDevices,
                    onConnect = onConnect,
                    modifier = Modifier.weight(0.65f).fillMaxHeight(),
                )
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                IntroPanel(Modifier.fillMaxWidth().weight(0.30f))
                ConnectPanel(
                    source = source,
                    onSource = { source = it },
                    host = host,
                    onHost = { host = it },
                    mode = mode,
                    onMode = { mode = it },
                    devices = uvcDevices,
                    selectedUvc = selectedUvc,
                    onSelectedUvc = { selectedUvc = it },
                    viscaEnabled = viscaEnabled,
                    onViscaEnabled = { viscaEnabled = it },
                    viscaHost = viscaHost,
                    onViscaHost = { viscaHost = it },
                    viscaPort = viscaPort,
                    onViscaPort = { viscaPort = it.filter(Char::isDigit).take(5) },
                    phase = phase,
                    failure = permissionFailure ?: failure,
                    busy = busy,
                    onRefreshUsb = model.session::refreshUvcDevices,
                    onConnect = onConnect,
                    modifier = Modifier.fillMaxWidth().weight(0.70f),
                )
            }
        }
    }
}

@Composable
private fun IntroPanel(modifier: Modifier) {
    Column(modifier.startupCard().padding(22.dp)) {
        Text(
            "OPENPTZ",
            color = StartupColors.muted,
            style = LiveType.ui(11f, FontWeight.SemiBold, LiveTypeDesign.Rounded).copy(letterSpacing = 1.5.sp),
        )
        Text(
            "One monitor. Any camera.",
            color = StartupColors.ink,
            style = LiveType.ui(28f, FontWeight.Bold, LiveTypeDesign.Rounded),
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            "Use Tail 2 over the network, or connect any UVC camera or capture card. VISCA over IP control can be paired independently with USB video.",
            color = StartupColors.muted,
            style = LiveType.ui(13f, design = LiveTypeDesign.Rounded).copy(lineHeight = 18.sp),
            modifier = Modifier.padding(top = 12.dp),
        )
        Spacer(Modifier.weight(1f))
        Text(
            "VISCA OVER IP  ·  USB UVC  ·  GPU ASSISTS",
            color = StartupColors.accent,
            style = LiveType.ui(10f, FontWeight.SemiBold, LiveTypeDesign.Rounded).copy(letterSpacing = 1.1.sp),
        )
    }
}

@Composable
private fun ConnectPanel(
    source: OpenPtzConnectionSource,
    onSource: (OpenPtzConnectionSource) -> Unit,
    host: String,
    onHost: (String) -> Unit,
    mode: Tail2PreviewMode,
    onMode: (Tail2PreviewMode) -> Unit,
    devices: List<UvcDeviceInfo>,
    selectedUvc: String?,
    onSelectedUvc: (String) -> Unit,
    viscaEnabled: Boolean,
    onViscaEnabled: (Boolean) -> Unit,
    viscaHost: String,
    onViscaHost: (String) -> Unit,
    viscaPort: String,
    onViscaPort: (String) -> Unit,
    phase: ConnectionPhase,
    failure: String?,
    busy: Boolean,
    onRefreshUsb: () -> Unit,
    onConnect: () -> Unit,
    modifier: Modifier,
) {
    val uriHandler = LocalUriHandler.current
    val needsUvc = source == OpenPtzConnectionSource.USB_VIDEO || mode == Tail2PreviewMode.UVC
    val validPort = (viscaPort.toIntOrNull() ?: 0) in 1..65_535
    val selectedDeviceRequired = source == OpenPtzConnectionSource.USB_VIDEO
    val canConnect =
        !busy &&
            (!selectedDeviceRequired || selectedUvc != null) &&
            if (source == OpenPtzConnectionSource.TAIL2) host.isNotBlank()
            else !viscaEnabled || (viscaHost.isNotBlank() && validPort)

    Column(modifier.startupCard().verticalScroll(rememberScrollState()).padding(22.dp)) {
        Text("CONNECTION", color = StartupColors.muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Video input and control",
            color = StartupColors.ink,
            style = LiveType.ui(21f, FontWeight.Bold, LiveTypeDesign.Rounded),
            modifier = Modifier.padding(top = 6.dp),
        )
        SegmentRow(
            entries = OpenPtzConnectionSource.entries,
            selected = source,
            label = { it.label },
            enabled = !busy,
            onSelected = onSource,
            modifier = Modifier.padding(top = 16.dp),
        )

        if (source == OpenPtzConnectionSource.TAIL2) {
            Text(
                "Tail 2 REST and VISCA control use the camera IP. Choose network video or a direct USB UVC feed.",
                color = StartupColors.muted,
                fontSize = 13.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(top = 12.dp),
            )
            OutlinedTextField(
                value = host,
                onValueChange = onHost,
                label = { Text("Tail 2 IP") },
                placeholder = { Text("192.168.88.10") },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            Text("PREVIEW INPUT", color = StartupColors.muted, fontSize = 10.sp, modifier = Modifier.padding(top = 16.dp))
            SegmentRow(
                entries = Tail2PreviewMode.entries,
                selected = mode,
                label = { if (it == Tail2PreviewMode.RTSP) "RTSP" else it.label },
                enabled = !busy,
                onSelected = onMode,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (mode == Tail2PreviewMode.NDI) NdiExplanation(uriHandler)
        } else {
            Text(
                "USB video is brand-independent. Enable VISCA only when the camera has a reachable network control address.",
                color = StartupColors.muted,
                fontSize = 13.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        if (needsUvc) {
            UvcDevicePicker(
                devices = devices,
                selected = selectedUvc,
                allowsAutoAttach = source == OpenPtzConnectionSource.TAIL2,
                enabled = !busy,
                onSelected = onSelectedUvc,
                onRefresh = onRefreshUsb,
            )
        }

        if (source == OpenPtzConnectionSource.USB_VIDEO) {
            Text("PTZ CONTROL", color = StartupColors.muted, fontSize = 10.sp, modifier = Modifier.padding(top = 16.dp))
            SegmentRow(
                entries = listOf(false, true),
                selected = viscaEnabled,
                label = { if (it) "VISCA IP" else "VIDEO ONLY" },
                enabled = !busy,
                onSelected = onViscaEnabled,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (viscaEnabled) {
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = viscaHost,
                        onValueChange = onViscaHost,
                        label = { Text("VISCA camera IP") },
                        placeholder = { Text("192.168.1.100") },
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = viscaPort,
                        onValueChange = onViscaPort,
                        label = { Text("UDP port") },
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier.weight(0.48f),
                    )
                }
            }
        }

        if (!failure.isNullOrBlank()) {
            Text(
                failure,
                color = StartupColors.destructive,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(top = 14.dp),
            )
        }
        Spacer(Modifier.height(22.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (busy) CircularProgressIndicator(color = StartupColors.accent, strokeWidth = 2.dp)
            Box(
                Modifier.weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (canConnect) StartupColors.accent else StartupColors.control)
                    .clickable(enabled = canConnect, onClick = onConnect)
                    .padding(vertical = 15.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (busy) "Connecting…"
                    else if (phase == ConnectionPhase.FAILED) "Try again"
                    else if (source == OpenPtzConnectionSource.TAIL2) "Connect Tail 2"
                    else "Open USB monitor",
                    color = StartupColors.darkText,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun UvcDevicePicker(
    devices: List<UvcDeviceInfo>,
    selected: String?,
    allowsAutoAttach: Boolean,
    enabled: Boolean,
    onSelected: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("USB UVC DEVICE", color = StartupColors.muted, fontSize = 10.sp)
        Text(
            "REFRESH",
            color = StartupColors.accent,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable(enabled = enabled, onClick = onRefresh).padding(6.dp),
        )
    }
    if (devices.isEmpty()) {
        Text(
            if (allowsAutoAttach) {
                "No UVC interface is visible yet. Connect Tail 2 by USB; Connect will switch it from MTP to UVC and wait for the new device."
            } else {
                "No UVC interface found. Connect a USB camera or HDMI/SDI capture card through an OTG adapter."
            },
            color = Color(0xFFFFC857),
            fontSize = 12.sp,
            lineHeight = 17.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
    } else {
        devices.forEach { device ->
            val active = device.deviceName == selected
            Box(
                Modifier.fillMaxWidth()
                    .padding(top = 7.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (active) StartupColors.accent else StartupColors.tile)
                    .clickable(enabled = enabled) { onSelected(device.deviceName) }
                    .padding(horizontal = 12.dp, vertical = 11.dp),
            ) {
                Text(
                    device.label,
                    color = if (active) StartupColors.darkText else StartupColors.ink,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun NdiExplanation(uriHandler: androidx.compose.ui.platform.UriHandler) {
    Text(
        if (!AndroidNdiReceiver.isAvailable) {
            "This build does not contain the local NDI Android runtime. OPENPTZ will use RTSP."
        } else if (!AndroidNdiReceiver.canDecodeTail2Hx) {
            "Tail 2 sends NDI HX3. The standard NDI Android SDK cannot decode HX on Android, so OPENPTZ will use low-latency RTSP. USB UVC is the direct, codec-free alternative."
        } else {
            "NDI HX3 receiver ready. Decoded frames enter the Vulkan exposure-assist pipeline."
        },
        color =
            if (AndroidNdiReceiver.isAvailable && AndroidNdiReceiver.canDecodeTail2Hx) StartupColors.accent
            else Color(0xFFFFC857),
        fontSize = 12.sp,
        lineHeight = 17.sp,
        modifier = Modifier.padding(top = 12.dp),
    )
    Text(
        "Learn about NDI® · NDI® is a registered trademark of Vizrt NDI AB.",
        color = StartupColors.muted,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        modifier = Modifier.padding(top = 7.dp).clickable { uriHandler.openUri("https://ndi.video/") },
    )
}

@Composable
private fun <T> SegmentRow(
    entries: List<T>,
    selected: T,
    label: (T) -> String,
    enabled: Boolean,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        entries.forEach { entry ->
            val active = entry == selected
            Box(
                Modifier.weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (active) StartupColors.accent else StartupColors.tile)
                    .clickable(enabled = enabled) { onSelected(entry) }
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(entry),
                    color = if (active) StartupColors.darkText else StartupColors.ink,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
