package dev.castbridge.app.ui.devices

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.castbridge.app.CastBridgeApplication
import dev.castbridge.app.R
import dev.castbridge.app.ui.components.ProtocolBadge
import dev.castbridge.app.ui.theme.CastBridgeTheme
import dev.castbridge.core.capture.CaptureConfigFactory
import dev.castbridge.core.capture.CaptureFormat
import dev.castbridge.core.capture.CaptureMode
import dev.castbridge.core.capture.CaptureState
import dev.castbridge.core.capture.ScreenCaptureRequest
import dev.castbridge.core.casting.CastingState
import dev.castbridge.core.model.CastDevice
import dev.castbridge.core.model.CastProtocol
import dev.castbridge.core.model.ChromecastDevice
import dev.castbridge.core.model.DiscoveryIssue
import dev.castbridge.core.model.RokuDevice
import dev.castbridge.core.streaming.StreamingState
import java.io.File
import kotlinx.coroutines.launch

@Composable
fun DeviceListRoute() {
    val context = LocalContext.current
    val application = context.applicationContext as CastBridgeApplication
    val viewModel: DeviceListViewModel = viewModel(
        factory = DeviceListViewModel.factory(application.container),
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LifecycleStartEffect(Unit) {
        viewModel.onStart()
        onStopOrDispose { viewModel.onStop() }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            val message = when (event) {
                is DeviceListEvent.ProbeSucceeded -> context.getString(
                    R.string.connection_snackbar_reachable,
                    event.deviceName,
                    event.latencyMillis,
                )
                is DeviceListEvent.ProbeFailed -> context.getString(
                    R.string.connection_snackbar_unreachable,
                    event.deviceName,
                    event.reason,
                )
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    var detailsDeviceId by rememberSaveable { mutableStateOf<String?>(null) }
    val detailsDevice = state.allDevices.firstOrNull { it.id == detailsDeviceId }

    val captureConfig = remember(context) {
        CaptureConfigFactory.forDisplay(context.resources.displayMetrics)
    }
    val clipboard = remember(context) {
        context.getSystemService(ClipboardManager::class.java)
    }
    val scope = rememberCoroutineScope()
    var pendingMode by remember { mutableStateOf(CaptureMode.RECORD) }

    val captureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            viewModel.startCapture(result.resultCode, data, captureConfig, pendingMode)
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        // The ongoing notification is helpful but not required for capture to work.
        captureLauncher.launch(ScreenCaptureRequest.intent(context))
    }

    val requestCapture: (CaptureMode) -> Unit = { mode ->
        pendingMode = mode
        val needsNotificationPermission =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED

        if (needsNotificationPermission) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            captureLauncher.launch(ScreenCaptureRequest.intent(context))
        }
    }

    val onCopyStreamUrl: (String) -> Unit = { url ->
        clipboard?.setPrimaryClip(ClipData.newPlainText("CastBridge stream", url))
        scope.launch {
            snackbarHostState.showSnackbar(context.getString(R.string.stream_copied))
        }
    }

    DeviceListScreen(
        state = state,
        snackbarHostState = snackbarHostState,
        onDeviceClick = viewModel::toggleSelection,
        onFilterSelected = viewModel::selectFilter,
        onShowDetails = { device -> detailsDeviceId = device.id },
        onTestConnection = viewModel::testConnection,
        onStartCapture = { requestCapture(CaptureMode.RECORD) },
        onStopCapture = viewModel::stopCapture,
        onDismissCapture = viewModel::dismissCaptureResult,
        onStartStreaming = { requestCapture(CaptureMode.STREAM) },
        onStopStreaming = viewModel::stopCapture,
        onDismissStreaming = viewModel::dismissStreamingResult,
        onCopyStreamUrl = onCopyStreamUrl,
        onCast = viewModel::castToSelected,
        onStopCast = viewModel::stopCasting,
        onDismissCast = viewModel::dismissCastingResult,
    )

    detailsDevice?.let { device ->
        DeviceDetailsSheet(
            device = device,
            probe = state.probe.takeIf { it.deviceId == device.id } ?: ProbeUiState(),
            onDismiss = { detailsDeviceId = null },
            onTestConnection = { viewModel.testConnection(device.id) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeviceListScreen(
    state: DeviceListUiState,
    snackbarHostState: SnackbarHostState,
    onDeviceClick: (String) -> Unit,
    onFilterSelected: (DeviceFilter) -> Unit,
    onShowDetails: (CastDevice) -> Unit,
    onTestConnection: (String) -> Unit,
    onStartCapture: () -> Unit,
    onStopCapture: () -> Unit,
    onDismissCapture: () -> Unit,
    onStartStreaming: () -> Unit,
    onStopStreaming: () -> Unit,
    onDismissStreaming: () -> Unit,
    onCopyStreamUrl: (String) -> Unit,
    onCast: () -> Unit,
    onStopCast: () -> Unit,
    onDismissCast: () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { CastBridgeTopBar(isScanning = state.isScanning) },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            AnimatedVisibility(
                visible = state.selectedDevice != null,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            ) {
                state.selectedDevice?.let { device ->
                    SelectedDeviceBar(
                        device = device,
                        isChecking = state.probe.isChecking && state.probe.deviceId == device.id,
                        onDetails = { onShowDetails(device) },
                        onTest = { onTestConnection(device.id) },
                    )
                }
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "header") {
                DeviceListHeader(state)
            }

            item(key = "capture") {
                CaptureCard(
                    state = state.capture,
                    canStart = !state.streaming.isActive(),
                    onStart = onStartCapture,
                    onStop = onStopCapture,
                    onDismiss = onDismissCapture,
                )
            }

            item(key = "stream") {
                StreamCard(
                    state = state.streaming,
                    canStart = !state.capture.isActive(),
                    selectedDevice = state.selectedDevice,
                    casting = state.casting,
                    onStart = onStartStreaming,
                    onStop = onStopStreaming,
                    onDismiss = onDismissStreaming,
                    onCopyUrl = onCopyStreamUrl,
                    onCast = onCast,
                    onStopCast = onStopCast,
                    onDismissCast = onDismissCast,
                )
            }

            if (state.allDevices.any { it.protocol == CastProtocol.GOOGLE_CAST } &&
                state.allDevices.any { it.protocol == CastProtocol.ROKU }
            ) {
                item(key = "filters") {
                    FilterRow(
                        selected = state.filter,
                        onSelected = onFilterSelected,
                    )
                }
            }

            items(
                items = state.issues,
                key = { "issue-${it.protocol.name}" },
            ) { issue ->
                DiscoveryIssueBanner(issue)
            }

            if (state.visibleDevices.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        state = state,
                        onShowAll = { onFilterSelected(DeviceFilter.ALL) },
                    )
                }
            } else {
                items(
                    items = state.visibleDevices,
                    key = { it.id },
                ) { device ->
                    DeviceCard(
                        device = device,
                        selected = device.id == state.selectedDeviceId,
                        onClick = { onDeviceClick(device.id) },
                    )
                }

                item(key = "footer") {
                    FooterNote()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CastBridgeTopBar(isScanning: Boolean) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_cast),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        actions = {
            ScanPill(
                isScanning = isScanning,
                modifier = Modifier.padding(end = 16.dp),
            )
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
    )
}

@Composable
private fun ScanPill(isScanning: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "scanPill")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "scanPulse",
    )
    val dotColor = if (isScanning) {
        MaterialTheme.colorScheme.primary.copy(alpha = pulse)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .background(dotColor, CircleShape),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(if (isScanning) R.string.scanning else R.string.idle),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DeviceListHeader(state: DeviceListUiState) {
    Column(Modifier.padding(top = 4.dp, bottom = 4.dp)) {
        Text(
            text = stringResource(R.string.nearby_devices),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.nearby_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.allDevices.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
            ) {
                Text(
                    text = pluralStringResource(
                        R.plurals.device_count,
                        state.allDevices.size,
                        state.allDevices.size,
                    ),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun FilterRow(
    selected: DeviceFilter,
    onSelected: (DeviceFilter) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selected == DeviceFilter.ALL,
            onClick = { onSelected(DeviceFilter.ALL) },
            label = { Text(stringResource(R.string.filter_all)) },
        )
        FilterChip(
            selected = selected == DeviceFilter.CHROMECAST,
            onClick = { onSelected(DeviceFilter.CHROMECAST) },
            label = { Text(stringResource(R.string.filter_chromecast)) },
        )
        FilterChip(
            selected = selected == DeviceFilter.ROKU,
            onClick = { onSelected(DeviceFilter.ROKU) },
            label = { Text(stringResource(R.string.filter_roku)) },
        )
    }
}

@Composable
private fun CaptureCard(
    state: CaptureState,
    canStart: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
        tonalElevation = 1.dp,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_record),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.capture_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.capture_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state is CaptureState.Running) {
                    RecordingPill()
                }
            }

            Spacer(Modifier.height(14.dp))

            when (state) {
                CaptureState.Idle -> {
                    Text(
                        text = stringResource(R.string.capture_idle_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = onStart,
                        enabled = canStart,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_record),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.capture_start))
                    }
                }

                CaptureState.Preparing -> CaptureProgress(stringResource(R.string.capture_preparing))

                CaptureState.Finalizing -> CaptureProgress(stringResource(R.string.capture_finalizing))

                is CaptureState.Running -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        CaptureStat(
                            label = stringResource(R.string.capture_stat_elapsed),
                            value = CaptureFormat.elapsed(state.stats.elapsedMillis),
                            modifier = Modifier.weight(1f),
                        )
                        CaptureStat(
                            label = stringResource(R.string.capture_stat_resolution),
                            value = CaptureFormat.resolution(state.stats.width, state.stats.height),
                            modifier = Modifier.weight(1f),
                        )
                        CaptureStat(
                            label = stringResource(R.string.capture_stat_size),
                            value = CaptureFormat.bytes(state.stats.bytes),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    OutlinedButton(
                        onClick = onStop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_stop),
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.capture_stop))
                    }
                }

                is CaptureState.Completed -> {
                    val result = state.result
                    Text(
                        text = stringResource(
                            R.string.capture_saved,
                            File(result.filePath).name,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        CaptureStat(
                            label = stringResource(R.string.capture_stat_duration),
                            value = CaptureFormat.elapsed(result.durationMillis),
                            modifier = Modifier.weight(1f),
                        )
                        CaptureStat(
                            label = stringResource(R.string.capture_stat_resolution),
                            value = CaptureFormat.resolution(result.width, result.height),
                            modifier = Modifier.weight(1f),
                        )
                        CaptureStat(
                            label = stringResource(R.string.capture_stat_size),
                            value = CaptureFormat.bytes(result.sizeBytes),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                        ) {
                            Text(stringResource(R.string.capture_dismiss))
                        }
                        Button(
                            onClick = onStart,
                            enabled = canStart,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                        ) {
                            Text(stringResource(R.string.capture_record_again))
                        }
                    }
                }

                is CaptureState.Failed -> {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = onStart,
                        enabled = canStart,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                    ) {
                        Text(stringResource(R.string.capture_retry))
                    }
                }
            }
        }
    }
}

@Composable
private fun StreamCard(
    state: StreamingState,
    canStart: Boolean,
    selectedDevice: CastDevice?,
    casting: CastingState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
    onCopyUrl: (String) -> Unit,
    onCast: () -> Unit,
    onStopCast: () -> Unit,
    onDismissCast: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
        tonalElevation = 1.dp,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_cast),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.stream_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.stream_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state is StreamingState.Live) {
                    PulsePill(
                        text = stringResource(R.string.stream_live),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            when (state) {
                StreamingState.Idle -> {
                    Text(
                        text = stringResource(R.string.stream_idle_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.stream_entire_screen_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = onStart,
                        enabled = canStart,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_cast),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.stream_start))
                    }
                }

                StreamingState.Preparing ->
                    CaptureProgress(stringResource(R.string.stream_preparing))

                StreamingState.Stopping ->
                    CaptureProgress(stringResource(R.string.stream_stopping))

                is StreamingState.Live -> {
                    StreamUrl(url = state.url, onCopy = { onCopyUrl(state.url) })
                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        CaptureStat(
                            label = stringResource(R.string.capture_stat_elapsed),
                            value = CaptureFormat.elapsed(state.stats.elapsedMillis),
                            modifier = Modifier.weight(1f),
                        )
                        CaptureStat(
                            label = stringResource(R.string.capture_stat_resolution),
                            value = CaptureFormat.resolution(state.stats.width, state.stats.height),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        CaptureStat(
                            label = stringResource(R.string.stream_stat_segments),
                            value = state.stats.segments.toString(),
                            modifier = Modifier.weight(1f),
                        )
                        CaptureStat(
                            label = stringResource(R.string.capture_stat_size),
                            value = CaptureFormat.bytes(state.stats.bytes),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    CastControls(
                        selectedDevice = selectedDevice,
                        casting = casting,
                        onCast = onCast,
                        onStopCast = onStopCast,
                        onDismissCast = onDismissCast,
                    )
                    Spacer(Modifier.height(14.dp))
                    OutlinedButton(
                        onClick = onStop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_stop),
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.stream_stop))
                    }
                }

                is StreamingState.Stopped -> {
                    Text(
                        text = stringResource(R.string.stream_stopped),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    state.summary?.let { summary ->
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            CaptureStat(
                                label = stringResource(R.string.capture_stat_duration),
                                value = CaptureFormat.elapsed(summary.durationMillis),
                                modifier = Modifier.weight(1f),
                            )
                            CaptureStat(
                                label = stringResource(R.string.stream_stat_segments),
                                value = summary.segments.toString(),
                                modifier = Modifier.weight(1f),
                            )
                            CaptureStat(
                                label = stringResource(R.string.capture_stat_size),
                                value = CaptureFormat.bytes(summary.bytes),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                        ) {
                            Text(stringResource(R.string.stream_dismiss))
                        }
                        Button(
                            onClick = onStart,
                            enabled = canStart,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                        ) {
                            Text(stringResource(R.string.stream_start_again))
                        }
                    }
                }

                is StreamingState.Failed -> {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = onStart,
                        enabled = canStart,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                    ) {
                        Text(stringResource(R.string.stream_retry))
                    }
                }
            }
        }
    }
}

@Composable
private fun StreamUrl(url: String, onCopy: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.stream_url_label).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 0.8.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCopy) {
                    Text(stringResource(R.string.stream_copy_url))
                }
            }
            Text(
                text = url,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.stream_url_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CastControls(
    selectedDevice: CastDevice?,
    casting: CastingState,
    onCast: () -> Unit,
    onStopCast: () -> Unit,
    onDismissCast: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
    ) {
        Column(Modifier.padding(12.dp)) {
            when (casting) {
                CastingState.Idle -> {
                    if (selectedDevice == null) {
                        Text(
                            text = stringResource(R.string.cast_select_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Button(
                            onClick = onCast,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_cast),
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.cast_play, selectedDevice.name))
                        }
                    }
                }

                is CastingState.Connecting -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.cast_connecting, casting.deviceName),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val otherDevice = selectedDevice?.takeIf { it.id != casting.deviceId }
                    if (otherDevice != null) {
                        Spacer(Modifier.height(10.dp))
                        SwitchDeviceButton(
                            deviceName = otherDevice.name,
                            onClick = onCast,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = onStopCast,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Text(stringResource(R.string.cast_cancel))
                    }
                }

                is CastingState.Playing -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_check),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.cast_playing, casting.deviceName),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = stringResource(
                                    R.string.cast_pairing_code,
                                    casting.pairingCode,
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.cast_pairing_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val otherDevice = selectedDevice?.takeIf { it.id != casting.deviceId }
                    if (otherDevice != null) {
                        Spacer(Modifier.height(10.dp))
                        SwitchDeviceButton(
                            deviceName = otherDevice.name,
                            onClick = onCast,
                        )
                        Spacer(Modifier.height(8.dp))
                    } else {
                        Spacer(Modifier.height(10.dp))
                    }
                    OutlinedButton(
                        onClick = onStopCast,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Text(stringResource(R.string.cast_stop))
                    }
                }

                is CastingState.Failed -> {
                    Text(
                        text = casting.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(
                            onClick = onDismissCast,
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp),
                        ) {
                            Text(stringResource(R.string.cast_dismiss))
                        }
                        Button(
                            onClick = onCast,
                            enabled = selectedDevice != null,
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp),
                        ) {
                            Text(stringResource(R.string.cast_retry))
                        }
                    }
                }

                is CastingState.Stopped -> {
                    Text(
                        text = stringResource(R.string.cast_stopped, casting.deviceName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(
                            onClick = onDismissCast,
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp),
                        ) {
                            Text(stringResource(R.string.cast_dismiss))
                        }
                        Button(
                            onClick = onCast,
                            enabled = selectedDevice != null,
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp),
                        ) {
                            Text(stringResource(R.string.cast_retry))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SwitchDeviceButton(
    deviceName: String,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_cast),
            contentDescription = null,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.cast_switch, deviceName))
    }
}

@Composable
private fun CaptureProgress(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CaptureStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 0.8.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RecordingPill() {
    PulsePill(
        text = stringResource(R.string.capture_live),
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun PulsePill(text: String, color: Color) {
    val transition = rememberInfiniteTransition(label = "statusPill")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "statusPulse",
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .background(color.copy(alpha = alpha), CircleShape),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            color = color,
        )
    }
}

private fun CaptureState.isActive(): Boolean =
    this is CaptureState.Preparing ||
        this is CaptureState.Running ||
        this is CaptureState.Finalizing

private fun StreamingState.isActive(): Boolean =
    this is StreamingState.Preparing ||
        this is StreamingState.Live ||
        this is StreamingState.Stopping

@Composable
private fun DeviceCard(
    device: CastDevice,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val background = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton,
            ),
        shape = RoundedCornerShape(20.dp),
        color = background,
        border = BorderStroke(1.dp, borderColor),
        tonalElevation = if (selected) 0.dp else 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProtocolBadge(protocol = device.protocol, size = 48.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = device.model ?: protocolLabel(device.protocol),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = device.host,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                )
            }
            Spacer(Modifier.width(8.dp))
            SelectionMark(selected = selected)
        }
    }
}

@Composable
private fun SelectionMark(selected: Boolean) {
    val primary = MaterialTheme.colorScheme.primary
    val modifier = if (selected) {
        Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(primary)
    } else {
        Modifier
            .size(24.dp)
            .border(
                width = 1.5.dp,
                color = MaterialTheme.colorScheme.outline,
                shape = CircleShape,
            )
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (selected) {
            Icon(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun SelectedDeviceBar(
    device: CastDevice,
    isChecking: Boolean,
    onDetails: () -> Unit,
    onTest: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 12.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProtocolBadge(protocol = device.protocol, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.selected_device_label).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onDetails) {
                Text(stringResource(R.string.details))
            }
            Spacer(Modifier.width(4.dp))
            Button(onClick = onTest, enabled = !isChecking) {
                if (isChecking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(stringResource(R.string.test))
                }
            }
        }
    }
}

@Composable
private fun DiscoveryIssueBanner(issue: DiscoveryIssue) {
    val protocol = protocolLabel(issue.protocol)
    val hint = stringResource(
        if (issue.protocol == CastProtocol.GOOGLE_CAST) {
            R.string.issue_chromecast_hint
        } else {
            R.string.issue_roku_hint
        },
    )

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
    ) {
        Row(modifier = Modifier.padding(14.dp)) {
            Icon(
                painter = painterResource(R.drawable.ic_warning),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = stringResource(R.string.issue_title, protocol),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = issue.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.75f),
                )
            }
        }
    }
}

@Composable
private fun EmptyState(
    state: DeviceListUiState,
    onShowAll: () -> Unit,
) {
    val filteredEmpty = state.allDevices.isNotEmpty() && state.visibleDevices.isEmpty()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (filteredEmpty) {
            ProtocolBadge(
                protocol = if (state.filter == DeviceFilter.ROKU) {
                    CastProtocol.ROKU
                } else {
                    CastProtocol.GOOGLE_CAST
                },
                size = 64.dp,
            )
            Spacer(Modifier.height(18.dp))
        } else {
            RadarGraphic(modifier = Modifier.size(188.dp))
            Spacer(Modifier.height(20.dp))
        }

        Text(
            text = if (filteredEmpty) {
                stringResource(R.string.empty_filtered_title, state.filter.label())
            } else {
                stringResource(R.string.empty_title)
            },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 360.dp),
        )

        if (filteredEmpty) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onShowAll) {
                Text(stringResource(R.string.show_all_devices))
            }
        } else {
            Spacer(Modifier.height(20.dp))
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            ) {
                Row(modifier = Modifier.padding(12.dp)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_warning),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.empty_tip),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun RadarGraphic(modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val transition = rememberInfiniteTransition(label = "radar")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3200, easing = LinearEasing),
        ),
        label = "radarAngle",
    )
    val pulse by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
        label = "radarPulse",
    )

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val maxRadius = size.minDimension / 2f

        for (ring in 1..3) {
            drawCircle(
                color = primary.copy(alpha = 0.10f + 0.03f * ring),
                radius = maxRadius * ring / 3f,
                center = center,
                style = Stroke(width = 1.dp.toPx()),
            )
        }

        rotate(degrees = angle, pivot = center) {
            drawArc(
                brush = Brush.sweepGradient(
                    colors = listOf(
                        primary.copy(alpha = 0f),
                        primary.copy(alpha = 0.28f),
                        primary.copy(alpha = 0f),
                    ),
                    center = center,
                ),
                startAngle = 0f,
                sweepAngle = 70f,
                useCenter = true,
                topLeft = Offset(center.x - maxRadius, center.y - maxRadius),
                size = Size(maxRadius * 2f, maxRadius * 2f),
            )
        }

        drawCircle(
            color = primary.copy(alpha = 0.18f * pulse),
            radius = 17.dp.toPx(),
            center = center,
        )
        drawCircle(color = primary, radius = 5.dp.toPx(), center = center)
        drawCircle(
            color = primary.copy(alpha = 0.55f),
            radius = 3.dp.toPx(),
            center = Offset(center.x + maxRadius * 0.55f, center.y - maxRadius * 0.20f),
        )
        drawCircle(
            color = primary.copy(alpha = 0.35f),
            radius = 2.5.dp.toPx(),
            center = Offset(center.x - maxRadius * 0.38f, center.y + maxRadius * 0.48f),
        )
    }
}

@Composable
private fun FooterNote() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.discovery_runs_automatically),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
internal fun protocolLabel(protocol: CastProtocol): String = stringResource(
    when (protocol) {
        CastProtocol.GOOGLE_CAST -> R.string.protocol_google_cast
        CastProtocol.ROKU -> R.string.protocol_roku
    },
)

@Composable
private fun DeviceFilter.label(): String = stringResource(
    when (this) {
        DeviceFilter.ALL -> R.string.filter_all
        DeviceFilter.CHROMECAST -> R.string.filter_chromecast
        DeviceFilter.ROKU -> R.string.filter_roku
    },
)

private val previewDevices = listOf(
    ChromecastDevice(
        id = "cast:living",
        name = "Living Room",
        host = "192.168.1.24",
        model = "Chromecast with Google TV",
        deviceUuid = "1234567890",
        lastSeenMillis = 1L,
    ),
    RokuDevice(
        id = "roku:bedroom",
        name = "Bedroom TV",
        host = "192.168.1.51",
        model = "Roku Streaming Stick 4K",
        serialNumber = "X001",
        lastSeenMillis = 1L,
    ),
)

@Preview(showBackground = true, backgroundColor = 0xFFF6F7FB, heightDp = 800)
@Composable
private fun DeviceListPopulatedPreview() {
    CastBridgeTheme(darkTheme = false) {
        DeviceListScreen(
            state = DeviceListUiState(
                allDevices = previewDevices,
                visibleDevices = previewDevices,
                selectedDeviceId = "cast:living",
                selectedDevice = previewDevices.first(),
                isScanning = true,
                completedCycles = 2,
            ),
            snackbarHostState = remember { SnackbarHostState() },
            onDeviceClick = {},
            onFilterSelected = {},
            onShowDetails = {},
            onTestConnection = {},
            onStartCapture = {},
            onStopCapture = {},
            onDismissCapture = {},
            onStartStreaming = {},
            onStopStreaming = {},
            onDismissStreaming = {},
            onCopyStreamUrl = {},
            onCast = {},
            onStopCast = {},
            onDismissCast = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0C0F16, heightDp = 800)
@Composable
private fun DeviceListEmptyPreview() {
    CastBridgeTheme(darkTheme = true) {
        DeviceListScreen(
            state = DeviceListUiState(isScanning = true),
            snackbarHostState = remember { SnackbarHostState() },
            onDeviceClick = {},
            onFilterSelected = {},
            onShowDetails = {},
            onTestConnection = {},
            onStartCapture = {},
            onStopCapture = {},
            onDismissCapture = {},
            onStartStreaming = {},
            onStopStreaming = {},
            onDismissStreaming = {},
            onCopyStreamUrl = {},
            onCast = {},
            onStopCast = {},
            onDismissCast = {},
        )
    }
}
