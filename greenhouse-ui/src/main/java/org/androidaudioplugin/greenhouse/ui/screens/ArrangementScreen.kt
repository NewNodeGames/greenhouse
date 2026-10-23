package org.androidaudioplugin.greenhouse.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.androidaudioplugin.greenhouse.core.MidiSequencer
import org.androidaudioplugin.greenhouse.project.Clip
import org.androidaudioplugin.greenhouse.project.Placement
import org.androidaudioplugin.greenhouse.project.Project
import org.androidaudioplugin.greenhouse.project.Track
import org.androidaudioplugin.greenhouse.project.addClip
import org.androidaudioplugin.greenhouse.project.movePlacement
import org.androidaudioplugin.greenhouse.project.movePlacementToTrack
import org.androidaudioplugin.greenhouse.project.placeClip
import org.androidaudioplugin.greenhouse.project.removeClip
import org.androidaudioplugin.greenhouse.project.removePlacement
import org.androidaudioplugin.greenhouse.project.repeatPlacement
import org.androidaudioplugin.greenhouse.project.setClipLength
import org.androidaudioplugin.greenhouse.ui.HostViewModel
import org.androidaudioplugin.greenhouse.ui.screens.rack.rememberPlayheadTick
import org.androidaudioplugin.greenhouse.ui.theme.BerryRose
import org.androidaudioplugin.greenhouse.ui.theme.BlossomCoral
import org.androidaudioplugin.greenhouse.ui.theme.PeriwinkleBlue
import org.androidaudioplugin.greenhouse.ui.theme.SignalGreen
import org.androidaudioplugin.greenhouse.ui.theme.SproutGreen
import org.androidaudioplugin.greenhouse.ui.theme.StudioBackground
import org.androidaudioplugin.greenhouse.ui.theme.StudioPanelBorder
import org.androidaudioplugin.greenhouse.ui.theme.StudioSurface
import org.androidaudioplugin.greenhouse.ui.theme.StudioSurfaceElevated
import org.androidaudioplugin.greenhouse.ui.theme.StudioSurfaceVariant
import org.androidaudioplugin.greenhouse.ui.theme.TextMuted
import org.androidaudioplugin.greenhouse.ui.theme.TextPrimary
import org.androidaudioplugin.greenhouse.ui.theme.TextSecondary
import org.androidaudioplugin.greenhouse.ui.theme.WarningOrange
import kotlin.math.max
import kotlin.math.roundToInt

private val SCREEN_PADDING = 12.dp
private val SECTION_SPACING = 8.dp
private val HEADER_BUTTON_SIZE = 40.dp
private val HEADER_ICON_SIZE = 22.dp
private val HEADER_TITLE_SIZE = 18.sp
// How wide a bar is drawn, from the most zoomed out to the most zoomed in
private val BAR_WIDTHS = listOf(24.dp, 40.dp, 64.dp, 96.dp, 144.dp, 220.dp)
private const val DEFAULT_ZOOM_INDEX = 2
// Following the playhead: it is brought back into view, a fifth of the way in, once it is this close to the edge
private val FOLLOW_EDGE_MARGIN = 24.dp
private const val FOLLOW_LEAD_FRACTION = 0.2f
private const val GHOST_ALPHA = 0.25f
private const val FADED_ALPHA = 0.35f
private val LANE_HEIGHT = 56.dp
private val LANE_SPACING = 4.dp
private val RULER_HEIGHT = 24.dp
private val LABEL_WIDTH = 40.dp
private val CORNER_RADIUS = 8.dp
private val CLIP_PADDING = 3.dp
private val CLIP_TITLE_HEIGHT = 14.dp
private val GRID_LINE_WIDTH = 0.5.dp
private val SELECTED_BORDER_WIDTH = 2.dp
private val PLAYHEAD_WIDTH = 2.dp
private val RECORD_DOT_SIZE = 12.dp
private val ACTIVE_DOT_SIZE = 6.dp
private val PANEL_BUTTON_HEIGHT = 36.dp
private val PANEL_PADDING = 10.dp
private val SMALL_TEXT = 10.sp
private val BODY_TEXT = 12.sp
private val BUTTON_TEXT = 13.sp
// Bars shown past the end of the song, to place clips beyond it
private const val EXTRA_BARS = 8
private const val NEW_CLIP_SHORT_BARS = 1
private const val NEW_CLIP_MEDIUM_BARS = 2
private const val NEW_CLIP_LONG_BARS = 4
// How many instances the repeat buttons place
private const val REPEAT_FEW = 4
private const val REPEAT_MANY = 8
// At least this many semitones stand for the height of a clip's preview: one note must not fill it
private const val MIN_PREVIEW_SEMITONES = 12
private const val MIN_PREVIEW_NOTE_PX = 2f
private const val BEYOND_SONG_ALPHA = 0.35f
private const val CLIP_FILL_ALPHA = 0.28f
private const val PLAYHEAD_ALPHA = 0.9f
private val TRACK_COLORS = listOf(SproutGreen, PeriwinkleBlue, BlossomCoral, WarningOrange)

/** Where a clip being moved would land: the track and bar it is over, and whether it fits there. */
private data class Ghost(
    val sourceTrack: Int,
    val placementId: Int,
    val track: Int,
    val startBar: Int,
    val lengthBars: Int,
    val isValid: Boolean
)

/** What the panel under the timeline acts on: an empty bar of a track, or one of its placements. */
private sealed interface Selection {
    data class Cell(val track: Int, val bar: Int) : Selection
    data class Block(val track: Int, val placementId: Int) : Selection
}

/**
 * The song as a timeline: a lane per track of the rack, with its clips placed along the bars. Tap an empty bar
 * to add a clip there, a clip to edit its placement. Tap the ruler to move the cursor, where playing starts;
 * tap a track's number to make it the one that is played and recorded on.
 */
@Composable
fun ArrangementScreen(
    viewModel: HostViewModel,
    onNavigateBack: () -> Unit,
    onEditClip: (trackIndex: Int, clipId: Int) -> Unit
) {
    BackHandler { onNavigateBack() }

    val arrangement = viewModel.project
    val project = arrangement.project
    val sequencer = viewModel.sequencer
    val tracks = project.tracks.take(viewModel.rack.numTracks)
    val activeTrack = viewModel.rack.activeTrackIndex
    val displayBars = (project.lengthBars + EXTRA_BARS).coerceAtMost(Project.MAX_LENGTH_BARS)
    var selection by remember { mutableStateOf<Selection?>(null) }
    val scrollState = rememberScrollState()
    val playheadTick = if (sequencer.isRunning) rememberPlayheadTick(sequencer) else null
    val density = LocalDensity.current
    var zoomIndex by remember { mutableIntStateOf(DEFAULT_ZOOM_INDEX) }
    var shownZoomIndex by remember { mutableIntStateOf(DEFAULT_ZOOM_INDEX) }
    var isFollowing by remember { mutableStateOf(true) }
    var viewportWidthPx by remember { mutableIntStateOf(0) }
    var ghost by remember { mutableStateOf<Ghost?>(null) }
    val barWidth = BAR_WIDTHS[zoomIndex]

    // A placement that is gone (undone, or replaced by a recording) is no longer selected
    val selectedBlock = selection as? Selection.Block
    val selectedPlacement = selectedBlock?.let { block ->
        project.tracks.getOrNull(block.track)?.placements?.firstOrNull { it.id == block.placementId }
    }

    // Zooming keeps the bar at the left edge about where it was: the offset scales with the bar width, once the
    // wider (or narrower) content has been measured, or its old limit would cut the scroll short
    LaunchedEffect(zoomIndex) {
        if (zoomIndex != shownZoomIndex) {
            val factor = BAR_WIDTHS[zoomIndex].value / BAR_WIDTHS[shownZoomIndex].value

            shownZoomIndex = zoomIndex
            withFrameNanos { }
            withFrameNanos { }
            scrollState.scrollTo((scrollState.value * factor).toInt())
        }
    }

    // Following: while playing, the timeline moves along when the playhead leaves the view (and when it loops
    // back to the start). Not while a finger is scrolling it, or it would fight the user.
    LaunchedEffect(isFollowing, playheadTick, barWidth) {
        val tickState = playheadTick

        if (isFollowing && tickState != null) {
            snapshotFlow { tickState.doubleValue }.collect { tick ->
                val barPx = with(density) { barWidth.toPx() }
                val x = (tick / MidiSequencer.TICKS_PER_BAR * barPx).toInt()
                val left = scrollState.value
                val edge = with(density) { FOLLOW_EDGE_MARGIN.toPx() }

                if (viewportWidthPx > 0 && !scrollState.isScrollInProgress && (x < left || x > left + viewportWidthPx - edge)) {
                    scrollState.scrollTo((x - viewportWidthPx * FOLLOW_LEAD_FRACTION).toInt().coerceAtLeast(0))
                }
            }
        }
    }

    /** Applies an edit; [refusal] is what to tell if it could not be made. */
    fun edit(refusal: String, transform: (Project) -> Project?) {
        if (!arrangement.edit(transform)) {
            arrangement.report(refusal)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(StudioBackground)
            .padding(SCREEN_PADDING),
        verticalArrangement = Arrangement.spacedBy(SECTION_SPACING)
    ) {
        ArrangementHeader(
            canUndo = arrangement.canUndo,
            canRedo = arrangement.canRedo,
            onBack = onNavigateBack,
            onUndo = {
                if (!arrangement.undo()) arrangement.report("Nothing to undo.")
            },
            onRedo = {
                if (!arrangement.redo()) arrangement.report("Nothing to redo.")
            }
        )

        TransportRow(
            isRunning = sequencer.isRunning,
            isRecording = sequencer.status.isRecording,
            lengthBars = project.lengthBars,
            onPlay = { sequencer.togglePlayback() },
            onRecord = { sequencer.toggleRecording() },
            onShorter = { arrangement.setLengthBars(project.minLengthBars - 1) },
            onLonger = { arrangement.setLengthBars(project.lengthBars + 1) }
        )

        ViewRow(
            canZoomOut = zoomIndex > 0,
            canZoomIn = zoomIndex < BAR_WIDTHS.size - 1,
            isFollowing = isFollowing,
            onZoomOut = { zoomIndex = (zoomIndex - 1).coerceAtLeast(0) },
            onZoomIn = { zoomIndex = (zoomIndex + 1).coerceAtMost(BAR_WIDTHS.size - 1) },
            onToggleFollow = { isFollowing = !isFollowing }
        )

        Row(modifier = Modifier.weight(1f)) {
            // The numbers of the tracks stay in place while the bars scroll
            Column(
                modifier = Modifier.width(LABEL_WIDTH),
                verticalArrangement = Arrangement.spacedBy(LANE_SPACING)
            ) {
                Spacer(modifier = Modifier.height(RULER_HEIGHT))

                tracks.forEachIndexed { index, track ->
                    TrackLabel(
                        number = index + 1,
                        track = track,
                        color = TRACK_COLORS[index % TRACK_COLORS.size],
                        isActive = index == activeTrack,
                        onClick = { viewModel.rack.selectActiveTrack(index) }
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .onSizeChanged { viewportWidthPx = it.width }
                    .horizontalScroll(scrollState)
            ) {
                Column(
                    modifier = Modifier.width(barWidth * displayBars),
                    verticalArrangement = Arrangement.spacedBy(LANE_SPACING)
                ) {
                    Ruler(
                        displayBars = displayBars,
                        songBars = project.lengthBars,
                        barWidth = barWidth,
                        onSeek = { bar -> sequencer.seekTo(bar.toLong() * MidiSequencer.TICKS_PER_BAR) }
                    )

                    tracks.forEachIndexed { index, track ->
                        Lane(
                            track = track,
                            color = TRACK_COLORS[index % TRACK_COLORS.size],
                            displayBars = displayBars,
                            songBars = project.lengthBars,
                            barWidth = barWidth,
                            selection = selection,
                            trackIndex = index,
                            ghost = ghost,
                            onSelect = { selection = it },
                            onMoveStart = { placement -> selection = Selection.Block(index, placement.id) },
                            onMove = { placement, movedX, movedY ->
                                // Where the clip would land: a bar for each bar width moved across, a track for each lane height down
                                val barPx = with(density) { barWidth.toPx() }
                                val lanePx = with(density) { (LANE_HEIGHT + LANE_SPACING).toPx() }
                                val targetTrack = (index + (movedY / lanePx).roundToInt()).coerceIn(0, tracks.size - 1)
                                val startBar = (placement.startBar + (movedX / barPx).roundToInt()).coerceAtLeast(0)
                                val clip = track.clip(placement.clipId)

                                if (clip != null) {
                                    ghost = Ghost(
                                        sourceTrack = index,
                                        placementId = placement.id,
                                        track = targetTrack,
                                        startBar = startBar,
                                        lengthBars = clip.lengthBars,
                                        isValid = project.movePlacementToTrack(index, placement.id, targetTrack, startBar) != null
                                    )
                                }
                            },
                            onMoveEnd = {
                                val landing = ghost

                                ghost = null

                                if (landing != null) {
                                    // Into another track the clip is copied, and the placement is a new one there
                                    val newId = if (landing.track == landing.sourceTrack) {
                                        landing.placementId
                                    } else {
                                        project.tracks[landing.track].nextPlacementId
                                    }

                                    if (arrangement.edit { it.movePlacementToTrack(landing.sourceTrack, landing.placementId, landing.track, landing.startBar) }) {
                                        selection = Selection.Block(landing.track, newId)
                                    } else {
                                        arrangement.report("There is no room there.")
                                    }
                                }
                            },
                            onMoveCancel = { ghost = null }
                        )
                    }
                }

                // The playhead while playing, the cursor otherwise: read while drawing, as it moves every frame
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .drawBehind {
                            val tick = playheadTick?.doubleValue ?: sequencer.startTick.toDouble()
                            val x = (tick / MidiSequencer.TICKS_PER_BAR * barWidth.toPx()).toFloat()

                            drawRect(
                                color = BerryRose.copy(alpha = PLAYHEAD_ALPHA),
                                topLeft = Offset(x - PLAYHEAD_WIDTH.toPx() / 2f, 0f),
                                size = Size(PLAYHEAD_WIDTH.toPx(), size.height)
                            )
                        }
                )
            }
        }

        SelectionPanel(
            project = project,
            selection = selection,
            selectedPlacement = selectedPlacement,
            onNewClip = { track, bar, bars ->
                val placementId = project.tracks.getOrNull(track)?.nextPlacementId

                edit("There is no room for a $bars-bar clip there.") { current ->
                    val clipId = current.tracks.getOrNull(track)?.nextClipId

                    if (clipId == null) null else current.addClip(track, bars).placeClip(track, clipId, bar)
                }

                if (placementId != null) {
                    selection = Selection.Block(track, placementId)
                }
            },
            onPlaceClip = { track, clipId, bar ->
                val placementId = project.tracks.getOrNull(track)?.nextPlacementId

                edit("That clip does not fit there.") { it.placeClip(track, clipId, bar) }

                if (placementId != null) {
                    selection = Selection.Block(track, placementId)
                }
            },
            hasClipboard = arrangement.clipboard != null,
            onCopy = { track, placementId ->
                if (arrangement.copyPlacement(track, placementId)) {
                    arrangement.report("Clip copied.")
                }
            },
            onCut = { track, placementId ->
                if (arrangement.cutPlacement(track, placementId)) {
                    selection = null
                    arrangement.report("Clip cut.")
                }
            },
            onPaste = { track, bar ->
                if (arrangement.paste(track, bar)) {
                    // The pasted clip is the selection
                    selection = arrangement.project.tracks.getOrNull(track)?.placements
                        ?.firstOrNull { it.startBar == bar }
                        ?.let { Selection.Block(track, it.id) }
                } else {
                    arrangement.report("There is no room to paste there.")
                }
            },
            onRepeat = { track, placementId, copies ->
                val before = project.tracks.getOrNull(track)?.placements?.size ?: 0

                edit("There is no room after it.") { it.repeatPlacement(track, placementId, copies) }

                val placements = arrangement.project.tracks.getOrNull(track)?.placements.orEmpty()
                val added = placements.size - before

                if (added > 0) {
                    // The last one made is selected, so that repeating again goes on from it
                    selection = Selection.Block(track, placements.maxOf { it.id })

                    if (added < copies) {
                        arrangement.report("Only $added fit.")
                    }
                }
            },
            onMove = { track, placement, deltaBars ->
                edit("There is no room there.") { it.movePlacement(track, placement.id, placement.startBar + deltaBars) }
            },
            onResize = { track, clip, deltaBars ->
                val bars = clip.lengthBars + deltaBars

                if (bars >= Clip.MIN_LENGTH_BARS) {
                    edit("Another clip is in the way.") { current ->
                        val resized = current.setClipLength(track, clip.id, bars)

                        // Refused when the placements that follow are in the way: nothing changes then
                        if (resized == current) null else resized
                    }
                }
            },
            onEditNotes = { track, clipId -> onEditClip(track, clipId) },
            onDeletePlacement = { track, placementId ->
                edit("") { it.removePlacement(track, placementId) }
                selection = null
            },
            onDeleteClip = { track, clipId ->
                edit("") { it.removeClip(track, clipId) }
                selection = null
            }
        )
    }
}

@Composable
private fun ArrangementHeader(
    canUndo: Boolean,
    canRedo: Boolean,
    onBack: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SECTION_SPACING)
    ) {
        RoundButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back to Studio Rack",
                tint = TextPrimary,
                modifier = Modifier.size(HEADER_ICON_SIZE)
            )
        }

        Text(
            text = "Arrangement",
            color = TextPrimary,
            fontSize = HEADER_TITLE_SIZE,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )

        RoundButton(onClick = onUndo, isEnabled = canUndo) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Undo,
                contentDescription = "Undo",
                tint = if (canUndo) TextPrimary else TextMuted,
                modifier = Modifier.size(HEADER_ICON_SIZE)
            )
        }

        RoundButton(onClick = onRedo, isEnabled = canRedo) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Redo,
                contentDescription = "Redo",
                tint = if (canRedo) TextPrimary else TextMuted,
                modifier = Modifier.size(HEADER_ICON_SIZE)
            )
        }
    }
}

@Composable
private fun TransportRow(
    isRunning: Boolean,
    isRecording: Boolean,
    lengthBars: Int,
    onPlay: () -> Unit,
    onRecord: () -> Unit,
    onShorter: () -> Unit,
    onLonger: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SECTION_SPACING)
    ) {
        RoundButton(onClick = onPlay, background = if (isRunning) SignalGreen else StudioSurfaceElevated) {
            Icon(
                imageVector = if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = if (isRunning) "Stop" else "Play from the cursor",
                tint = if (isRunning) StudioBackground else TextPrimary,
                modifier = Modifier.size(HEADER_ICON_SIZE)
            )
        }

        RoundButton(onClick = onRecord, background = StudioSurfaceElevated) {
            Box(
                modifier = Modifier
                    .size(RECORD_DOT_SIZE)
                    .clip(CircleShape)
                    .background(if (isRecording) BerryRose else BerryRose.copy(alpha = BEYOND_SONG_ALPHA))
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        Text(text = "Song", color = TextSecondary, fontSize = BODY_TEXT)

        RoundButton(onClick = onShorter) {
            Icon(Icons.Default.Remove, contentDescription = "Shorter", tint = TextPrimary, modifier = Modifier.size(HEADER_ICON_SIZE))
        }

        Text(text = "$lengthBars bars", color = TextPrimary, fontSize = BUTTON_TEXT, fontWeight = FontWeight.Bold)

        RoundButton(onClick = onLonger) {
            Icon(Icons.Default.Add, contentDescription = "Longer", tint = TextPrimary, modifier = Modifier.size(HEADER_ICON_SIZE))
        }
    }
}

/** Zoom of the timeline, and whether it follows the playhead while playing. */
@Composable
private fun ViewRow(
    canZoomOut: Boolean,
    canZoomIn: Boolean,
    isFollowing: Boolean,
    onZoomOut: () -> Unit,
    onZoomIn: () -> Unit,
    onToggleFollow: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SECTION_SPACING)
    ) {
        Text(text = "Zoom", color = TextSecondary, fontSize = BODY_TEXT)

        RoundButton(onClick = onZoomOut, isEnabled = canZoomOut) {
            Icon(Icons.Default.Remove, contentDescription = "Zoom out", tint = if (canZoomOut) TextPrimary else TextMuted, modifier = Modifier.size(HEADER_ICON_SIZE))
        }

        RoundButton(onClick = onZoomIn, isEnabled = canZoomIn) {
            Icon(Icons.Default.Add, contentDescription = "Zoom in", tint = if (canZoomIn) TextPrimary else TextMuted, modifier = Modifier.size(HEADER_ICON_SIZE))
        }

        Spacer(modifier = Modifier.weight(1f))

        Box(
            modifier = Modifier
                .height(PANEL_BUTTON_HEIGHT)
                .clip(RoundedCornerShape(CORNER_RADIUS))
                .background(if (isFollowing) SproutGreen else StudioSurfaceElevated)
                .clickable(onClick = onToggleFollow)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Follow",
                color = if (isFollowing) StudioBackground else TextPrimary,
                fontSize = BUTTON_TEXT,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun RoundButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isEnabled: Boolean = true,
    background: Color = StudioSurfaceElevated,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .size(HEADER_BUTTON_SIZE)
            .clip(CircleShape)
            .background(background)
            .clickable(enabled = isEnabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

/** The number of a track, at the left of its lane. The track being played and recorded on has a dot. */
@Composable
private fun TrackLabel(number: Int, track: Track, color: Color, isActive: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .width(LABEL_WIDTH - LANE_SPACING)
            .height(LANE_HEIGHT)
            .clip(RoundedCornerShape(CORNER_RADIUS))
            .background(if (isActive) StudioSurfaceVariant else StudioSurface)
            .border(if (isActive) SELECTED_BORDER_WIDTH else GRID_LINE_WIDTH, if (isActive) color else StudioPanelBorder, RoundedCornerShape(CORNER_RADIUS))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "$number",
                color = if (track.isMuted) TextMuted else TextPrimary,
                fontSize = BUTTON_TEXT,
                fontWeight = FontWeight.Bold
            )

            if (isActive) {
                Box(
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .size(ACTIVE_DOT_SIZE)
                        .clip(CircleShape)
                        .background(color)
                )
            }
        }
    }
}

/** The bar numbers; tap one to put the cursor at its start. */
@Composable
private fun Ruler(displayBars: Int, songBars: Int, barWidth: Dp, onSeek: (Int) -> Unit) {
    Row(modifier = Modifier.height(RULER_HEIGHT)) {
        for (bar in 0 until displayBars) {
            Box(
                modifier = Modifier
                    .width(barWidth)
                    .fillMaxHeight()
                    .border(GRID_LINE_WIDTH, StudioPanelBorder)
                    .clickable { onSeek(bar) },
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = "${bar + 1}",
                    color = if (bar < songBars) TextSecondary else TextMuted,
                    fontSize = SMALL_TEXT,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }
    }
}

/** One track along the bars: a cell for each bar, and the clips placed over them. */
@Composable
private fun Lane(
    track: Track,
    color: Color,
    displayBars: Int,
    songBars: Int,
    barWidth: Dp,
    selection: Selection?,
    trackIndex: Int,
    ghost: Ghost?,
    onSelect: (Selection) -> Unit,
    onMoveStart: (Placement) -> Unit,
    onMove: (placement: Placement, movedX: Float, movedY: Float) -> Unit,
    onMoveEnd: () -> Unit,
    onMoveCancel: () -> Unit
) {
    Box(
        modifier = Modifier
            .width(barWidth * displayBars)
            .height(LANE_HEIGHT)
    ) {
        Row {
            for (bar in 0 until displayBars) {
                val isSelected = selection == Selection.Cell(trackIndex, bar)

                Box(
                    modifier = Modifier
                        .width(barWidth)
                        .fillMaxHeight()
                        .background(
                            when {
                                isSelected -> color.copy(alpha = CLIP_FILL_ALPHA)
                                bar < songBars -> StudioSurface
                                else -> StudioSurface.copy(alpha = BEYOND_SONG_ALPHA)
                            }
                        )
                        .border(GRID_LINE_WIDTH, StudioPanelBorder)
                        .clickable { onSelect(Selection.Cell(trackIndex, bar)) }
                )
            }
        }

        for (placement in track.placements) {
            val clip = track.clip(placement.clipId) ?: continue

            ClipBlock(
                clip = clip,
                color = color,
                isSelected = selection == Selection.Block(trackIndex, placement.id),
                // The clip being moved stays where it is, faint, until it is dropped
                isFaded = ghost != null && ghost.sourceTrack == trackIndex && ghost.placementId == placement.id,
                modifier = Modifier
                    .offset(x = barWidth * placement.startBar)
                    .width(barWidth * clip.lengthBars)
                    .fillMaxHeight(),
                onClick = { onSelect(Selection.Block(trackIndex, placement.id)) },
                onMoveStart = { onMoveStart(placement) },
                onMove = { movedX, movedY -> onMove(placement, movedX, movedY) },
                onMoveEnd = onMoveEnd,
                onMoveCancel = onMoveCancel
            )
        }

        // Where the clip being moved would land, if it is over this track: orange when it does not fit
        if (ghost != null && ghost.track == trackIndex) {
            val ghostColor = if (ghost.isValid) color else WarningOrange
            val ghostShape = RoundedCornerShape(CORNER_RADIUS)

            Box(
                modifier = Modifier
                    .offset(x = barWidth * ghost.startBar)
                    .width(barWidth * ghost.lengthBars)
                    .fillMaxHeight()
                    .padding(CLIP_PADDING)
                    .clip(ghostShape)
                    .background(ghostColor.copy(alpha = GHOST_ALPHA))
                    .border(SELECTED_BORDER_WIDTH, ghostColor, ghostShape)
            )
        }
    }
}

/** A clip placed in a lane: its name and a preview of its notes. */
@Composable
private fun ClipBlock(
    clip: Clip,
    color: Color,
    isSelected: Boolean,
    isFaded: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    onMoveStart: () -> Unit,
    onMove: (movedX: Float, movedY: Float) -> Unit,
    onMoveEnd: () -> Unit,
    onMoveCancel: () -> Unit
) {
    val shape = RoundedCornerShape(CORNER_RADIUS)
    // The gesture lives as long as the block: it reads what changes through these
    val latestMoveStart by rememberUpdatedState(onMoveStart)
    val latestMove by rememberUpdatedState(onMove)
    val latestMoveEnd by rememberUpdatedState(onMoveEnd)
    val latestMoveCancel by rememberUpdatedState(onMoveCancel)

    Box(
        modifier = modifier
            .alpha(if (isFaded) FADED_ALPHA else 1f)
            .padding(CLIP_PADDING)
            .clip(shape)
            .background(color.copy(alpha = CLIP_FILL_ALPHA))
            .border(if (isSelected) SELECTED_BORDER_WIDTH else GRID_LINE_WIDTH, color, shape)
            .clickable(onClick = onClick)
            .pointerInput(Unit) {
                var movedX = 0f
                var movedY = 0f

                // Press and hold, then drag: the clip is carried, and dropped where the finger lifts
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        movedX = 0f
                        movedY = 0f
                        latestMoveStart()
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        movedX += dragAmount.x
                        movedY += dragAmount.y
                        latestMove(movedX, movedY)
                    },
                    onDragEnd = { latestMoveEnd() },
                    onDragCancel = { latestMoveCancel() }
                )
            }
    ) {
        Text(
            text = clip.name,
            color = TextPrimary,
            fontSize = SMALL_TEXT,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp)
        )

        Canvas(
            modifier = Modifier
                .matchParentSize()
                .padding(top = CLIP_TITLE_HEIGHT, start = 2.dp, end = 2.dp, bottom = 2.dp)
        ) {
            if (clip.notes.isEmpty()) {
                return@Canvas
            }

            val lowest = clip.notes.minOf { it.note }
            val highest = clip.notes.maxOf { it.note }
            val semitones = max(highest - lowest + 1, MIN_PREVIEW_SEMITONES)
            val noteHeight = max(size.height / semitones, MIN_PREVIEW_NOTE_PX)

            for (note in clip.notes) {
                val x = note.startTick.toFloat() / clip.lengthTicks * size.width
                val width = max(note.durationTicks.toFloat() / clip.lengthTicks * size.width, MIN_PREVIEW_NOTE_PX)
                val y = size.height - (note.note - lowest + 1) * noteHeight

                drawRect(color = color, topLeft = Offset(x, y), size = Size(width, noteHeight))
            }
        }
    }
}

/** What can be done with the selection: add a clip in an empty bar, or edit a placed clip. */
@Composable
private fun SelectionPanel(
    project: Project,
    selection: Selection?,
    selectedPlacement: Placement?,
    onNewClip: (track: Int, bar: Int, bars: Int) -> Unit,
    onPlaceClip: (track: Int, clipId: Int, bar: Int) -> Unit,
    hasClipboard: Boolean,
    onCopy: (track: Int, placementId: Int) -> Unit,
    onCut: (track: Int, placementId: Int) -> Unit,
    onPaste: (track: Int, bar: Int) -> Unit,
    onRepeat: (track: Int, placementId: Int, copies: Int) -> Unit,
    onMove: (track: Int, placement: Placement, deltaBars: Int) -> Unit,
    onResize: (track: Int, clip: Clip, deltaBars: Int) -> Unit,
    onEditNotes: (track: Int, clipId: Int) -> Unit,
    onDeletePlacement: (track: Int, placementId: Int) -> Unit,
    onDeleteClip: (track: Int, clipId: Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(CORNER_RADIUS))
            .background(StudioSurfaceVariant)
            .padding(PANEL_PADDING),
        verticalArrangement = Arrangement.spacedBy(SECTION_SPACING)
    ) {
        val block = selection as? Selection.Block
        val cell = selection as? Selection.Cell
        val blockTrack = block?.let { project.tracks.getOrNull(it.track) }
        val blockClip = if (blockTrack != null && selectedPlacement != null) blockTrack.clip(selectedPlacement.clipId) else null

        when {
            block != null && blockTrack != null && selectedPlacement != null && blockClip != null -> {
                Text(
                    text = "${blockClip.name} - bar ${selectedPlacement.startBar + 1}, ${blockClip.lengthBars} bars, ${blockClip.notes.size} notes",
                    color = TextPrimary,
                    fontSize = BODY_TEXT
                )

                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(SECTION_SPACING)
                ) {
                    PanelButton("Edit notes") { onEditNotes(block.track, blockClip.id) }
                    PanelButton("Copy") { onCopy(block.track, selectedPlacement.id) }
                    PanelButton("Cut") { onCut(block.track, selectedPlacement.id) }
                    PanelButton("Paste after", isEnabled = hasClipboard) {
                        onPaste(block.track, selectedPlacement.startBar + blockClip.lengthBars)
                    }
                    // Another instance of the same clip right after, selected, so that pressing again goes on from it
                    PanelButton("Repeat") { onRepeat(block.track, selectedPlacement.id, 1) }
                    PanelButton("Repeat x4") { onRepeat(block.track, selectedPlacement.id, REPEAT_FEW) }
                    PanelButton("Repeat x8") { onRepeat(block.track, selectedPlacement.id, REPEAT_MANY) }
                    PanelButton("< Bar") { onMove(block.track, selectedPlacement, -1) }
                    PanelButton("Bar >") { onMove(block.track, selectedPlacement, 1) }
                    PanelButton("Shorter") { onResize(block.track, blockClip, -1) }
                    PanelButton("Longer") { onResize(block.track, blockClip, 1) }
                    PanelButton("Remove", isDanger = true) { onDeletePlacement(block.track, selectedPlacement.id) }
                    PanelButton("Delete clip", isDanger = true) { onDeleteClip(block.track, blockClip.id) }
                }
            }

            cell != null && project.tracks.getOrNull(cell.track) != null -> {
                val track = project.tracks[cell.track]

                Text(
                    text = "Track ${cell.track + 1}, bar ${cell.bar + 1}: add a clip here",
                    color = TextPrimary,
                    fontSize = BODY_TEXT
                )

                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(SECTION_SPACING)
                ) {
                    PanelButton("New, 1 bar") { onNewClip(cell.track, cell.bar, NEW_CLIP_SHORT_BARS) }
                    PanelButton("New, 2 bars") { onNewClip(cell.track, cell.bar, NEW_CLIP_MEDIUM_BARS) }
                    PanelButton("New, 4 bars") { onNewClip(cell.track, cell.bar, NEW_CLIP_LONG_BARS) }
                    PanelButton("Paste", isEnabled = hasClipboard) { onPaste(cell.track, cell.bar) }

                    // The clips this track already has, to place another instance of one
                    for (clip in track.clips) {
                        PanelButton("Place ${clip.name}") { onPlaceClip(cell.track, clip.id, cell.bar) }
                    }
                }
            }

            else -> {
                Text(
                    text = "Tap a bar to add a clip, a clip to edit it or open its notes (press it and hold to move it, to another track too), the ruler to set where playing starts, a track number to play and record on it.",
                    color = TextSecondary,
                    fontSize = BODY_TEXT
                )
            }
        }
    }
}

@Composable
private fun PanelButton(label: String, isDanger: Boolean = false, isEnabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(PANEL_BUTTON_HEIGHT)
            .clip(RoundedCornerShape(CORNER_RADIUS))
            .background(StudioSurfaceElevated)
            .clickable(enabled = isEnabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = when {
                !isEnabled -> TextMuted
                isDanger -> WarningOrange
                else -> TextPrimary
            },
            fontSize = BUTTON_TEXT,
            fontWeight = FontWeight.Medium
        )
    }
}
