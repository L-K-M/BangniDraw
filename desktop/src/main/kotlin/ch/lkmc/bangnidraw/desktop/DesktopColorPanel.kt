package ch.lkmc.bangnidraw.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ch.lkmc.bangnidraw.engine.core.HsvChannel
import ch.lkmc.bangnidraw.engine.core.HsvSelection
import java.util.Locale

/**
 * The colour panel the top strip's swatch opens — the desktop stand-in for
 * `:app`'s `ColorPanel`. It carries the two controls this shell has always
 * had (the starter swatches and the HSV channels) inside the panel surface
 * the Android chrome uses, instead of a permanently docked sidebar.
 *
 * The HSV channels go through [HsvChannel] rather than raw floats, so their
 * ranges and steps cannot drift from the Android panel's.
 */
@Composable
internal fun DesktopColorPanel(
    selection: HsvSelection,
    onSelection: (HsvSelection) -> Unit,
    onSwatch: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 3.dp,
        modifier = modifier
            .width(PANEL_WIDTH)
            .semantics {
                liveRegion = LiveRegionMode.Polite
                paneTitle = "Colour"
            },
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(SECTION_GAP),
            modifier = Modifier
                .padding(PANEL_PADDING)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Colour", style = MaterialTheme.typography.titleSmall)
                Box(Modifier.weight(1f))
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Close colour panel")
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SWATCH_GAP),
            ) {
                Box(
                    Modifier
                        .size(CURRENT_SWATCH)
                        .clip(RoundedCornerShape(SWATCH_RADIUS))
                        .background(Color(selection.argb))
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outline,
                            RoundedCornerShape(SWATCH_RADIUS),
                        ),
                )
                Text(
                    hex(selection.argb),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // The starter palette wraps rather than scrolling sideways: a
            // 320 dp panel fits six per row, and a hidden swatch is a swatch
            // nobody uses.
            for (row in DesktopPalette.SWATCHES.chunked(SWATCHES_PER_ROW)) {
                Row(horizontalArrangement = Arrangement.spacedBy(SWATCH_GAP)) {
                    for (swatch in row) {
                        Box(
                            Modifier
                                .size(SWATCH)
                                .clip(RoundedCornerShape(SWATCH_RADIUS))
                                .background(Color(swatch))
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.outline,
                                    RoundedCornerShape(SWATCH_RADIUS),
                                )
                                .semantics {
                                    contentDescription = hex(swatch)
                                }
                                // clickable, not a raw pointer loop: the
                                // swatch needs focus traversal, Enter/Space,
                                // and a primary-button-only click. The loop
                                // this replaces fired on any button.
                                .clickable { onSwatch(swatch) },
                        )
                    }
                }
            }

            for (channel in HsvChannel.entries) {
                ChannelSlider(channel, selection, onSelection)
            }
        }
    }
}

@Composable
private fun ChannelSlider(
    channel: HsvChannel,
    selection: HsvSelection,
    onSelection: (HsvSelection) -> Unit,
) {
    val value = channel.read(selection.hsv)
    Column {
        Text(
            "${channelLabel(channel)} ${channelValue(channel, value)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // The same thin track the rail uses: Material Expressive's default
        // slider brings an asymmetric thumb and a terminal marker that read
        // as a different app beside it.
        DesktopThinSlider(
            value = value,
            range = channel.range,
            axis = DesktopSliderAxis.Horizontal,
            description = channelLabel(channel),
            onValueChange = { onSelection(selection.commit(channel.replace(selection.hsv, it))) },
            fillWidth = true,
            // The channel's own stops, as `:app`'s panel passes them: hue to
            // the degree, saturation and value to the percent. Without them
            // the desktop panel drifts off the Android one by design, which
            // is the whole reason HsvChannel carries `steps`.
            steps = channel.steps,
        )
    }
}

/** Locale.ROOT: a locale with its own digits would shape the hex code. */
private fun hex(argb: Int): String = "#%06X".format(Locale.ROOT, argb and RGB_MASK)

/**
 * The reading `:app` shows for the same selection: `%.0f` rounds where
 * `toInt` would truncate — a stored saturation of 0.6299 reads 63 there and
 * would read 62 here — and the degree and percent signs are its units.
 */
private fun channelValue(channel: HsvChannel, value: Float): String {
    val rounded = "%.0f".format(Locale.ROOT, value)
    return if (channel == HsvChannel.HUE) "$rounded°" else "$rounded%"
}

private fun channelLabel(channel: HsvChannel): String = when (channel) {
    HsvChannel.HUE -> "Hue"
    HsvChannel.SATURATION -> "Saturation"
    HsvChannel.VALUE -> "Value"
}

private val PANEL_WIDTH = 320.dp
private val PANEL_PADDING = 16.dp
private val SECTION_GAP = 10.dp
private val SWATCH = 32.dp
private val CURRENT_SWATCH = 40.dp
private val SWATCH_GAP = 8.dp
private val SWATCH_RADIUS = 6.dp
private const val SWATCHES_PER_ROW = 6
private const val RGB_MASK = 0xFFFFFF
