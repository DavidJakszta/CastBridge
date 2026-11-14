package dev.castbridge.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.castbridge.app.R
import dev.castbridge.core.model.CastProtocol

internal val CastProtocol.accentColor: Color
    get() = when (this) {
        CastProtocol.GOOGLE_CAST -> Color(0xFF4C8DFF)
        CastProtocol.ROKU -> Color(0xFF8B5CF6)
    }

private val CastProtocol.iconRes: Int
    get() = when (this) {
        CastProtocol.GOOGLE_CAST -> R.drawable.ic_cast
        CastProtocol.ROKU -> R.drawable.ic_tv
    }

@Composable
internal fun ProtocolBadge(
    protocol: CastProtocol,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
) {
    val accent = protocol.accentColor
    val protocolName = stringResource(
        when (protocol) {
            CastProtocol.GOOGLE_CAST -> R.string.protocol_google_cast
            CastProtocol.ROKU -> R.string.protocol_roku
        },
    )
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.3f))
            .background(accent.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(protocol.iconRes),
            contentDescription = stringResource(R.string.content_desc_protocol_icon, protocolName),
            tint = accent,
            modifier = Modifier.size(size * 0.55f),
        )
    }
}
