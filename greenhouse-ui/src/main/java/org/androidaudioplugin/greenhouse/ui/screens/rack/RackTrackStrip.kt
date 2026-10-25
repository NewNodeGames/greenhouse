package org.androidaudioplugin.greenhouse.ui.screens.rack

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.androidaudioplugin.greenhouse.data.SlotHostSettings
import org.androidaudioplugin.greenhouse.project.Track
import org.androidaudioplugin.greenhouse.ui.components.HostFader
import org.androidaudioplugin.greenhouse.ui.components.LevelFader
import org.androidaudioplugin.greenhouse.ui.theme.BlossomCoral
import org.androidaudioplugin.greenhouse.ui.theme.SignalGreen
import org.androidaudioplugin.greenhouse.ui.theme.StudioBackground
import org.androidaudioplugin.greenhouse.ui.theme.StudioPanelBorder
import org.androidaudioplugin.greenhouse.ui.theme.StudioSurface
import org.androidaudioplugin.greenhouse.ui.theme.StudioSurfaceElevated
import org.androidaudioplugin.greenhouse.ui.theme.StudioSurfaceVariant
import org.androidaudioplugin.greenhouse.ui.theme.TextMuted
import org.androidaudioplugin.greenhouse.ui.theme.TextPrimary
import org.androidaudioplugin.greenhouse.ui.theme.TextSecondary
import org.androidaudioplugin.greenhouse.ui.theme.WarningOrange

private val TRACK_CARD_HEIGHT = 84.dp
private val TRACK_CARD_CORNER_RADIUS = 10.dp
private val TRACK_CARD_PADDING = 6.dp
private val TRACK_CARD_SPACING = 4.dp
private val TRACK_BUTTON_SIZE = 22.dp
private val TRACK_BUTTON_CORNER_RADIUS = 6.dp
private val TRACK_FADER_HEIGHT = 18.dp
private val TRACK_FADER_LABEL_WIDTH = 22.dp
private val TRACK_FADER_LABEL_SIZE = 8.sp
// The pan fader works in positions: the left end is all the way left, the middle the centre
private const val PAN_CENTER_POSITION = 0.5f
private val SELECTED_BORDER_WIDTH = 1.5.dp
private val UNSELECTED_BORDER_WIDTH = 1.dp
private val TRACK_NUMBER_SIZE = 13.sp
private val TRACK_BUTTON_LABEL_SIZE = 11.sp

/**
 * One card per track of the rack, side by side: tap to show the track (its chain of slots and where the
 * keyboard plays), M and S to mute and solo it, and faders for its level and its pan in the master mix
 * (double-tap one to go back to unity, or to the centre).
 */
@Composable
fun RackTrackStrip(
    tracks: List<Track>,
    activeTrack: Int,
    onSelectTrack: (Int) -> Unit,
    onToggleMute: (Int) -> Unit,
    onToggleSolo: (Int) -> Unit,
    onLevelChange: (Int, Float) -> Unit,
    onPanChange: (Int, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(TRACK_CARD_SPACING)
    ) {
        tracks.forEachIndexed { index, track ->
            val isSelected = index == activeTrack
            val shape = RoundedCornerShape(TRACK_CARD_CORNER_RADIUS)

            Card(
                modifier = Modifier
                    .weight(1f)
                    .height(TRACK_CARD_HEIGHT)
                    .border(
                        if (isSelected) SELECTED_BORDER_WIDTH else UNSELECTED_BORDER_WIDTH,
                        if (isSelected) BlossomCoral else StudioPanelBorder,
                        shape
                    )
                    .clip(shape)
                    .clickable { onSelectTrack(index) },
                shape = shape,
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) StudioSurfaceVariant else StudioSurface
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(TRACK_CARD_PADDING),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(TRACK_CARD_SPACING)
                    ) {
                        Text(
                            text = "${index + 1}",
                            color = if (isSelected) TextPrimary else TextSecondary,
                            fontSize = TRACK_NUMBER_SIZE,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )

                        TrackToggleButton("M", track.isMuted, WarningOrange) { onToggleMute(index) }
                        TrackToggleButton("S", track.isSoloed, SignalGreen) { onToggleSolo(index) }
                    }

                    TrackFaderRow(label = "VOL") {
                        LevelFader(
                            levelDb = track.levelDb,
                            minLevelDb = SlotHostSettings.MIN_LEVEL_DB,
                            maxLevelDb = SlotHostSettings.MAX_LEVEL_DB,
                            defaultLevelDb = SlotHostSettings.DEFAULT_LEVEL_DB,
                            onLevelChange = { levelDb -> onLevelChange(index, levelDb) },
                            modifier = Modifier
                                .weight(1f)
                                .height(TRACK_FADER_HEIGHT)
                        )
                    }

                    TrackFaderRow(label = "PAN") {
                        HostFader(
                            position = panToPosition(track.pan),
                            defaultPosition = PAN_CENTER_POSITION,
                            onPositionChange = { position -> onPanChange(index, positionToPan(position)) },
                            modifier = Modifier
                                .weight(1f)
                                .height(TRACK_FADER_HEIGHT)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackToggleButton(label: String, isOn: Boolean, activeColor: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(TRACK_BUTTON_SIZE)
            .clip(RoundedCornerShape(TRACK_BUTTON_CORNER_RADIUS))
            .background(if (isOn) activeColor else StudioSurfaceElevated)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (isOn) StudioBackground else TextSecondary,
            fontSize = TRACK_BUTTON_LABEL_SIZE,
            fontWeight = FontWeight.Bold
        )
    }
}

/** A small label and its fader (the content, which takes the rest of the row). */
@Composable
private fun TrackFaderRow(label: String, fader: @Composable RowScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            color = TextMuted,
            fontSize = TRACK_FADER_LABEL_SIZE,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(TRACK_FADER_LABEL_WIDTH)
        )

        fader()
    }
}

private fun panToPosition(pan: Float): Float {
    return (pan.coerceIn(Track.MIN_PAN, Track.MAX_PAN) - Track.MIN_PAN) / (Track.MAX_PAN - Track.MIN_PAN)
}

private fun positionToPan(position: Float): Float {
    return Track.MIN_PAN + position.coerceIn(0f, 1f) * (Track.MAX_PAN - Track.MIN_PAN)
}
