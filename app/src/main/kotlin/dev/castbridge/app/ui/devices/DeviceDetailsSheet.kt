package dev.castbridge.app.ui.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.castbridge.app.R
import dev.castbridge.app.ui.components.ProtocolBadge
import dev.castbridge.core.model.CastDevice
import dev.castbridge.core.model.ChromecastDevice
import dev.castbridge.core.model.ProbeResult
import dev.castbridge.core.model.RokuDevice

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeviceDetailsSheet(
    device: CastDevice,
    probe: ProbeUiState,
    onDismiss: () -> Unit,
    onTestConnection: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 28.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProtocolBadge(protocol = device.protocol, size = 56.dp)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        text = device.name,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = device.model ?: protocolLabel(device.protocol),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            ProbeStatus(probe)
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            Spacer(Modifier.height(12.dp))

            InfoRow(stringResource(R.string.device_protocol), protocolLabel(device.protocol))
            InfoRow(stringResource(R.string.device_ip), device.host)
            InfoRow(
                label = stringResource(R.string.device_model),
                value = device.model ?: stringResource(R.string.unknown),
            )

            when (device) {
                is RokuDevice -> {
                    InfoRow(
                        label = stringResource(R.string.device_port),
                        value = device.ecpPort.toString(),
                    )
                    InfoRow(
                        label = stringResource(R.string.device_serial),
                        value = device.serialNumber ?: stringResource(R.string.unknown),
                    )
                    InfoRow(
                        label = stringResource(R.string.device_vendor),
                        value = device.vendorName ?: "Roku",
                    )
                }
                is ChromecastDevice -> {
                    InfoRow(
                        label = stringResource(R.string.device_port),
                        value = device.port.toString(),
                    )
                    InfoRow(
                        label = stringResource(R.string.device_cast_id),
                        value = device.deviceUuid ?: stringResource(R.string.unknown),
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onTestConnection,
                enabled = !probe.isChecking,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
            ) {
                if (probe.isChecking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.testing))
                } else {
                    Text(stringResource(R.string.test_connection))
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.details_footer),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ProbeStatus(probe: ProbeUiState) {
    when (val result = probe.result) {
        is ProbeResult.Reachable -> StatusBanner(
            iconRes = R.drawable.ic_check,
            iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            text = stringResource(R.string.connection_reachable, result.latencyMillis),
        )
        is ProbeResult.Unreachable -> StatusBanner(
            iconRes = R.drawable.ic_warning,
            iconTint = MaterialTheme.colorScheme.onErrorContainer,
            containerColor = MaterialTheme.colorScheme.errorContainer,
            text = stringResource(R.string.connection_unreachable, result.reason),
        )
        null -> Unit
    }
}

@Composable
private fun StatusBanner(
    iconRes: Int,
    iconTint: Color,
    containerColor: Color,
    text: String,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = containerColor,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = iconTint,
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.width(112.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
        )
    }
}
