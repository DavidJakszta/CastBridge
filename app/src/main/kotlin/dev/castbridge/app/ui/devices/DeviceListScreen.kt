package dev.castbridge.app.ui.devices

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.castbridge.app.CastBridgeApplication
import dev.castbridge.app.R
import dev.castbridge.app.ui.components.ProtocolBadge
import dev.castbridge.app.ui.theme.CastBridgeTheme
import dev.castbridge.core.model.CastDevice
import dev.castbridge.core.model.CastProtocol
import dev.castbridge.core.model.ChromecastDevice
import dev.castbridge.core.model.DiscoveryIssue
import dev.castbridge.core.model.RokuDevice

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

    DeviceListScreen(
        state = state,
        snackbarHostState = snackbarHostState,
        onDeviceClick = viewModel::toggleSelection,
        onFilterSelected = viewModel::selectFilter,
        onShowDetails = { device -> detailsDeviceId = device.id },
        onTestConnection = viewModel::testConnection,
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
        )
    }
}
