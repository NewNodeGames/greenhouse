package org.androidaudioplugin.greenhouse.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.androidaudioplugin.greenhouse.core.MidiSequencer
import org.androidaudioplugin.greenhouse.project.ClipEditing
import org.androidaudioplugin.greenhouse.project.ClipNote
import org.androidaudioplugin.greenhouse.project.Project
import org.androidaudioplugin.greenhouse.project.editNotes
import org.androidaudioplugin.greenhouse.project.setProbabilitySeed
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
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

private val SCREEN_PADDING = 12.dp
private val SECTION_SPACING = 8.dp
private val HEADER_BUTTON_SIZE = 40.dp
private val HEADER_ICON_SIZE = 22.dp
private val HEADER_TITLE_SIZE = 16.sp
private val ROW_HEIGHT = 18.dp
private val KEY_WIDTH = 46.dp
private val RULER_HEIGHT = 22.dp
private val CORNER_RADIUS = 8.dp
private val BUTTON_HEIGHT = 34.dp
private val PANEL_PADDING = 10.dp
private val SMALL_TEXT = 10.sp
private val BODY_TEXT = 12.sp
private val BUTTON_TEXT = 13.sp
private val NOTE_INSET = 1.dp
private val PLAYHEAD_WIDTH = 2.dp
private val SELECTION_STROKE_WIDTH = 2.dp
private val BAR_WIDTHS = listOf(120.dp, 200.dp, 320.dp)
private const val DEFAULT_ZOOM_INDEX = 1
// The grid choices in ticks: whole, half, quarter, eighth, sixteenth and thirty-second notes, then the triplets of
// a quarter, an eighth and a sixteenth (three of them take the time of two)
private val GRID_TICKS = listOf(
    MidiSequencer.TICKS_PER_BAR,
    MidiSequencer.TICKS_PER_BAR / 2,
    MidiSequencer.PPQ,
    MidiSequencer.PPQ / 2,
    MidiSequencer.PPQ / 4,
    MidiSequencer.PPQ / 8,
    MidiSequencer.PPQ * 2 / 3,
    MidiSequencer.PPQ / 3,
    MidiSequencer.PPQ / 6
)
private val GRID_LABELS = listOf("1/1", "1/2", "1/4", "1/8", "1/16", "1/32", "1/4T", "1/8T", "1/16T")
private const val DEFAULT_GRID_INDEX = 4
private const val PITCH_COUNT = 128
private const val HIGHEST_NOTE = PITCH_COUNT - 1
private const val SEMITONES_PER_OCTAVE = 12
private const val MIDDLE_C = 60
// How many rows above the middle of the notes the first view starts at
private const val INITIAL_ROWS_ABOVE = 8
private const val VELOCITY_STEP = 0.05f
private const val PERCENT = 100
private val VELOCITY_LANE_HEIGHT = 64.dp
private val VELOCITY_BAR_WIDTH = 6.dp
private val VELOCITY_BAR_SPACING = 7.dp
private val VELOCITY_HIT_RADIUS = 14.dp
private val PROBABILITY_BAR_HEIGHT = 3.dp
private const val NO_NOTE = -1
private const val NEVER_PLAYED_ALPHA = 0.15f
private const val VELOCITY_GUIDE_ALPHA = 0.4f
private val VELOCITY_GUIDES = listOf(0.25f, 0.5f, 0.75f, 1f)
private const val AUDITION_MILLIS = 250L
private const val AUDITION_VELOCITY = 0.8f
private const val NOTE_MIN_ALPHA = 0.4f
private const val BLACK_KEY_ALPHA = 0.5f
private const val LINE_ALPHA = 0.5f
private const val BEAT_LINE_ALPHA = 0.8f
private const val PLAYHEAD_ALPHA = 0.9f
private val BLACK_KEYS = setOf(1, 3, 6, 8, 10)
private val NOTE_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
private val TRACK_COLORS = listOf(SproutGreen, PeriwinkleBlue, BlossomCoral, WarningOrange)

/**
 * The notes of one clip, as a grid of time across and pitch down, with a keyboard at the left. Tap an empty
 * place to add a note there (as long as the grid), a note to select it or not. Press a note and hold, then
 * drag, to move the notes selected; the buttons under the grid do the rest: delete, copy, quantize, move by
 * the grid or by semitones, make longer or shorter, change the velocity. Every change is a step of the
 * arrangement's undo, and is heard at once if the song is playing.
 */
@Composable
fun PianoRollScreen(
    viewModel: HostViewModel,
    trackIndex: Int,
    clipId: Int,
    onNavigateBack: () -> Unit
) {
    BackHandler { onNavigateBack() }

    val arrangement = viewModel.project
    val project = arrangement.project
    val sequencer = viewModel.sequencer
    val track = project.tracks.getOrNull(trackIndex)
    val clip = track?.clip(clipId)

    // The notes played to hear what is added or moved go to the instrument of this track
    LaunchedEffect(trackIndex) {
        viewModel.rack.selectActiveTrack(trackIndex)
    }

    // The clip is gone (it was deleted, or the edit that made it was undone): there is nothing to edit
    LaunchedEffect(clip == null) {
        if (clip == null) {
            onNavigateBack()
        }
    }

    if (track == null || clip == null) {
        return
    }

    val color = TRACK_COLORS[trackIndex % TRACK_COLORS.size]
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val horizontalScroll = rememberScrollState()
    val verticalScroll = rememberScrollState()
    var selectedIds by remember { mutableStateOf(setOf<Int>()) }
    var gridIndex by remember { mutableIntStateOf(DEFAULT_GRID_INDEX) }
    var zoomIndex by remember { mutableIntStateOf(DEFAULT_ZOOM_INDEX) }
    // The panel of buttons under the grid takes room the grid could use: it can be put away
    var isPanelVisible by remember { mutableStateOf(true) }
    var dragTickDelta by remember { mutableIntStateOf(0) }
    var dragPitchDelta by remember { mutableIntStateOf(0) }
    // The note whose velocity is being dragged in the velocity lane, and the velocity it has now
    var velocityDragId by remember { mutableIntStateOf(NO_NOTE) }
    var velocityDragValue by remember { mutableFloatStateOf(0f) }

    val gridTicks = GRID_TICKS[gridIndex]
    val barWidth = BAR_WIDTHS[zoomIndex]
    val gridWidth = barWidth * clip.lengthBars
    val gridHeight = ROW_HEIGHT * PITCH_COUNT
    // What is selected, among the notes that are still there (an undo can remove some)
    val selection = selectedIds.filter { id -> clip.notes.any { it.id == id } }.toSet()
    val isDragging = dragTickDelta != 0 || dragPitchDelta != 0
    val shownNotes = if (isDragging) {
        ClipEditing.move(clip.notes, selection, dragTickDelta, dragPitchDelta, clip.lengthTicks)
    } else {
        clip.notes
    }
    val playheadTick = if (sequencer.isRunning) rememberPlayheadTick(sequencer) else null
    val firstPlacement = track.placements.firstOrNull { it.clipId == clipId }

    // The gestures live as long as the screen: they read what changes through these
    val latestClip by rememberUpdatedState(clip)
    val latestSelection by rememberUpdatedState(selection)
    val latestGridTicks by rememberUpdatedState(gridTicks)
    val latestBarWidth by rememberUpdatedState(barWidth)
    val chordIndexes = chordIndexesOf(clip.notes)
    val latestChordIndexes by rememberUpdatedState(chordIndexes)

    // Starts the first view around the notes of the clip
    LaunchedEffect(clipId) {
        val pitches = clip.notes.map { it.note }
        val center = if (pitches.isEmpty()) MIDDLE_C else (pitches.min() + pitches.max()) / 2
        val rowPx = with(density) { ROW_HEIGHT.toPx() }

        verticalScroll.scrollTo(((HIGHEST_NOTE - center - INITIAL_ROWS_ABOVE).coerceAtLeast(0) * rowPx).toInt())
    }

    fun audition(note: Int) {
        scope.launch {
            viewModel.audio.engine.sendNoteOn(note, AUDITION_VELOCITY)
            delay(AUDITION_MILLIS)
            viewModel.audio.engine.sendNoteOff(note)
        }
    }

    /** One step of the arrangement's undo: [change] gets the notes of the clip and its length in ticks. */
    fun edit(change: (List<ClipNote>, Int) -> List<ClipNote>) {
        arrangement.edit { it.editNotes(trackIndex, clipId, change) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(StudioBackground)
            .padding(SCREEN_PADDING),
        verticalArrangement = Arrangement.spacedBy(SECTION_SPACING)
    ) {
        RollHeader(
            title = "Track ${trackIndex + 1} - ${clip.name} (${clip.lengthBars} bars)",
            canUndo = arrangement.canUndo,
            canRedo = arrangement.canRedo,
            isRunning = sequencer.isRunning,
            canPlay = firstPlacement != null,
            isPanelVisible = isPanelVisible,
            onTogglePanel = { isPanelVisible = !isPanelVisible },
            onBack = onNavigateBack,
            onUndo = {
                if (!arrangement.undo()) arrangement.report("Nothing to undo.")
            },
            onRedo = {
                if (!arrangement.redo()) arrangement.report("Nothing to redo.")
            },
            onPlay = {
                if (!sequencer.isRunning && firstPlacement != null) {
                    sequencer.seekTo(firstPlacement.startBar.toLong() * MidiSequencer.TICKS_PER_BAR)
                }

                sequencer.togglePlayback()
            }
        )

        // The grid the notes snap to, and how wide the bars are drawn
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SECTION_SPACING)
        ) {
            Text(text = "Grid", color = TextSecondary, fontSize = BODY_TEXT)

            GRID_LABELS.forEachIndexed { index, label ->
                RollButton(label, isOn = index == gridIndex) { gridIndex = index }
            }

            Text(text = "Zoom", color = TextSecondary, fontSize = BODY_TEXT)

            RollButton("-") { zoomIndex = (zoomIndex - 1).coerceAtLeast(0) }
            RollButton("+") { zoomIndex = (zoomIndex + 1).coerceAtMost(BAR_WIDTHS.size - 1) }
        }

        // The bar numbers, scrolling along with the grid
        Row(modifier = Modifier.fillMaxWidth()) {
            Spacer(modifier = Modifier.width(KEY_WIDTH))

            Box(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(horizontalScroll)
            ) {
                Canvas(
                    modifier = Modifier
                        .width(gridWidth)
                        .height(RULER_HEIGHT)
                ) {
                    val barPx = barWidth.toPx()

                    for (bar in 0 until clip.lengthBars) {
                        drawText(
                            textMeasurer = textMeasurer,
                            text = "${bar + 1}",
                            topLeft = Offset(bar * barPx + 4.dp.toPx(), 2.dp.toPx()),
                            style = TextStyle(color = TextSecondary, fontSize = SMALL_TEXT)
                        )
                    }
                }
            }
        }

        // The keyboard and the grid, which scroll together up and down; the grid scrolls across as well
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(verticalScroll)
        ) {
            Row {
                Canvas(
                    modifier = Modifier
                        .width(KEY_WIDTH)
                        .height(gridHeight)
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = { offset ->
                                val pitch = HIGHEST_NOTE - (offset.y / ROW_HEIGHT.toPx()).toInt()

                                if (pitch in 0..HIGHEST_NOTE) {
                                    audition(pitch)
                                }
                            })
                        }
                ) {
                    val rowPx = ROW_HEIGHT.toPx()

                    for (pitch in 0..HIGHEST_NOTE) {
                        val y = (HIGHEST_NOTE - pitch) * rowPx
                        val isBlack = (pitch % SEMITONES_PER_OCTAVE) in BLACK_KEYS

                        drawRect(
                            color = if (isBlack) StudioSurfaceElevated else TextPrimary.copy(alpha = BLACK_KEY_ALPHA),
                            topLeft = Offset(0f, y),
                            size = Size(size.width, rowPx - 1f)
                        )

                        if (pitch % SEMITONES_PER_OCTAVE == 0) {
                            drawText(
                                textMeasurer = textMeasurer,
                                text = "C${pitch / SEMITONES_PER_OCTAVE - 1}",
                                topLeft = Offset(4.dp.toPx(), y + 2.dp.toPx()),
                                style = TextStyle(color = StudioBackground, fontSize = SMALL_TEXT, fontWeight = FontWeight.Bold)
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(horizontalScroll)
                ) {
                    Box(
                        modifier = Modifier
                            .width(gridWidth)
                            .height(gridHeight)
                    ) {
                        Canvas(
                            modifier = Modifier
                                .width(gridWidth)
                                .height(gridHeight)
                                .pointerInput(Unit) {
                                    detectTapGestures(onTap = { offset ->
                                        val rowPx = ROW_HEIGHT.toPx()
                                        val ticksPerPx = MidiSequencer.TICKS_PER_BAR / latestBarWidth.toPx()
                                        val tick = (offset.x * ticksPerPx).toInt()
                                        val pitch = (HIGHEST_NOTE - (offset.y / rowPx).toInt()).coerceIn(0, HIGHEST_NOTE)
                                        val hit = latestClip.notes.lastOrNull { it.note == pitch && tick >= it.startTick && tick < it.endTick }

                                        if (hit != null) {
                                            selectedIds = if (hit.id in latestSelection) latestSelection - hit.id else latestSelection + hit.id
                                        } else {
                                            // A new note starts on the grid line before the tap, and lasts a grid step
                                            val start = tick / latestGridTicks * latestGridTicks
                                            val newId = latestClip.nextNoteId

                                            edit { notes, lengthTicks ->
                                                ClipEditing.addNote(notes, pitch, start, latestGridTicks, ClipNote.DEFAULT_VELOCITY, lengthTicks)
                                            }
                                            selectedIds = setOf(newId)
                                            audition(pitch)
                                        }
                                    })
                                }
                                .pointerInput(Unit) {
                                    var isMoving = false
                                    var movedX = 0f
                                    var movedY = 0f

                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { offset ->
                                            val rowPx = ROW_HEIGHT.toPx()
                                            val ticksPerPx = MidiSequencer.TICKS_PER_BAR / latestBarWidth.toPx()
                                            val tick = (offset.x * ticksPerPx).toInt()
                                            val pitch = (HIGHEST_NOTE - (offset.y / rowPx).toInt()).coerceIn(0, HIGHEST_NOTE)
                                            val hit = latestClip.notes.lastOrNull { it.note == pitch && tick >= it.startTick && tick < it.endTick }

                                            // Only a note can be picked up; if it was not selected, it is the selection
                                            isMoving = hit != null
                                            movedX = 0f
                                            movedY = 0f

                                            if (hit != null && hit.id !in latestSelection) {
                                                selectedIds = setOf(hit.id)
                                            }
                                        },
                                        onDrag = { change, dragAmount ->
                                            if (isMoving) {
                                                change.consume()
                                                movedX += dragAmount.x
                                                movedY += dragAmount.y

                                                // Snapped as it moves: a grid step in time, a semitone in pitch
                                                val tickDelta = (movedX * MidiSequencer.TICKS_PER_BAR / latestBarWidth.toPx() / latestGridTicks).roundToInt()
                                                dragTickDelta = tickDelta * latestGridTicks
                                                dragPitchDelta = -(movedY / ROW_HEIGHT.toPx()).roundToInt()
                                            }
                                        },
                                        onDragEnd = {
                                            val tickDelta = dragTickDelta
                                            val pitchDelta = dragPitchDelta
                                            val moved = latestSelection

                                            isMoving = false
                                            dragTickDelta = 0
                                            dragPitchDelta = 0

                                            if (tickDelta != 0 || pitchDelta != 0) {
                                                edit { notes, lengthTicks -> ClipEditing.move(notes, moved, tickDelta, pitchDelta, lengthTicks) }
                                            }
                                        },
                                        onDragCancel = {
                                            isMoving = false
                                            dragTickDelta = 0
                                            dragPitchDelta = 0
                                        }
                                    )
                                }
                        ) {
                            val rowPx = ROW_HEIGHT.toPx()
                            val barPx = barWidth.toPx()
                            val tickPx = barPx / MidiSequencer.TICKS_PER_BAR

                            // The rows of the black keys are darker
                            for (pitch in 0..HIGHEST_NOTE) {
                                val y = (HIGHEST_NOTE - pitch) * rowPx

                                drawRect(
                                    color = if ((pitch % SEMITONES_PER_OCTAVE) in BLACK_KEYS) StudioSurface else StudioSurfaceVariant,
                                    topLeft = Offset(0f, y),
                                    size = Size(size.width, rowPx)
                                )

                                drawLine(
                                    color = StudioPanelBorder.copy(alpha = LINE_ALPHA),
                                    start = Offset(0f, y),
                                    end = Offset(size.width, y)
                                )
                            }

                            // The grid lines; the beats and the bars are stronger
                            var tick = 0

                            while (tick <= clip.lengthTicks) {
                                val x = tick * tickPx
                                val isBar = tick % MidiSequencer.TICKS_PER_BAR == 0
                                val isBeat = tick % MidiSequencer.PPQ == 0

                                drawLine(
                                    color = if (isBar) TextMuted else StudioPanelBorder.copy(alpha = if (isBeat) BEAT_LINE_ALPHA else LINE_ALPHA),
                                    start = Offset(x, 0f),
                                    end = Offset(x, size.height),
                                    strokeWidth = if (isBar) 2f else 1f
                                )

                                tick += gridTicks
                            }

                            val inset = NOTE_INSET.toPx()

                            for (note in shownNotes) {
                                val topLeft = Offset(note.startTick * tickPx + inset, (HIGHEST_NOTE - note.note) * rowPx + inset)
                                val noteSize = Size(maxOf(note.durationTicks * tickPx - 2 * inset, 2f), rowPx - 2 * inset)

                                // A note that will not be played is faint; one that may not be has a bar for its chance
                                val noteAlpha = NOTE_MIN_ALPHA + (1f - NOTE_MIN_ALPHA) * note.velocity

                                drawRect(
                                    color = color.copy(alpha = if (note.probability == 0) NEVER_PLAYED_ALPHA else noteAlpha),
                                    topLeft = topLeft,
                                    size = noteSize
                                )

                                if (note.probability == 0) {
                                    drawRect(
                                        color = color,
                                        topLeft = topLeft,
                                        size = noteSize,
                                        style = Stroke(width = 1f)
                                    )
                                } else if (note.probability < ClipNote.DEFAULT_PROBABILITY) {
                                    val barHeight = PROBABILITY_BAR_HEIGHT.toPx()

                                    drawRect(
                                        color = TextPrimary,
                                        topLeft = Offset(topLeft.x, topLeft.y + noteSize.height - barHeight),
                                        size = Size(noteSize.width * note.probability / PERCENT, barHeight)
                                    )
                                }

                                if (note.id in selection) {
                                    drawRect(
                                        color = TextPrimary,
                                        topLeft = topLeft,
                                        size = noteSize,
                                        style = Stroke(width = SELECTION_STROKE_WIDTH.toPx())
                                    )
                                }
                            }
                        }

                        // The playhead while the song plays, where it is in this clip: read while drawing
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .drawBehind {
                                    val tick = playheadTick?.doubleValue ?: return@drawBehind

                                    // Where the clip is placed that the playhead is in; nothing if it is in none
                                    val placement = track.placements.firstOrNull {
                                        it.clipId == clipId &&
                                            tick >= it.startBar.toDouble() * MidiSequencer.TICKS_PER_BAR &&
                                            tick < it.startBar.toDouble() * MidiSequencer.TICKS_PER_BAR + clip.lengthTicks
                                    } ?: return@drawBehind

                                    val clipTick = tick - placement.startBar.toDouble() * MidiSequencer.TICKS_PER_BAR
                                    val x = (clipTick / MidiSequencer.TICKS_PER_BAR * barWidth.toPx()).toFloat()

                                    drawRect(
                                        color = BerryRose.copy(alpha = PLAYHEAD_ALPHA),
                                        topLeft = Offset(x - PLAYHEAD_WIDTH.toPx() / 2f, 0f),
                                        size = Size(PLAYHEAD_WIDTH.toPx(), size.height)
                                    )
                                }
                        )
                    }
                }
            }
        }

        // The velocity of each note as a bar under the grid: drag a bar up or down to set the velocity of that note
        Row(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .width(KEY_WIDTH)
                    .height(VELOCITY_LANE_HEIGHT),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "Vel", color = TextSecondary, fontSize = SMALL_TEXT)
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(horizontalScroll)
            ) {
                Canvas(
                    modifier = Modifier
                        .width(gridWidth)
                        .height(VELOCITY_LANE_HEIGHT)
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = { offset ->
                                val hit = velocityBarAt(
                                    offset.x, latestClip.notes, latestChordIndexes,
                                    latestBarWidth.toPx() / MidiSequencer.TICKS_PER_BAR,
                                    VELOCITY_BAR_SPACING.toPx(), VELOCITY_BAR_WIDTH.toPx(), VELOCITY_HIT_RADIUS.toPx()
                                )

                                if (hit != null) {
                                    val velocity = velocityAt(offset.y, VELOCITY_LANE_HEIGHT.toPx())

                                    edit { notes, _ -> ClipEditing.setVelocity(notes, setOf(hit.id), velocity) }
                                    audition(hit.note)

                                    if (hit.id !in latestSelection) {
                                        selectedIds = setOf(hit.id)
                                    }
                                }
                            })
                        }
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    val hit = velocityBarAt(
                                        offset.x, latestClip.notes, latestChordIndexes,
                                        latestBarWidth.toPx() / MidiSequencer.TICKS_PER_BAR,
                                        VELOCITY_BAR_SPACING.toPx(), VELOCITY_BAR_WIDTH.toPx(), VELOCITY_HIT_RADIUS.toPx()
                                    )

                                    velocityDragId = hit?.id ?: NO_NOTE

                                    if (hit != null) {
                                        velocityDragValue = velocityAt(offset.y, VELOCITY_LANE_HEIGHT.toPx())
                                    }
                                },
                                onDrag = { change, _ ->
                                    if (velocityDragId != NO_NOTE) {
                                        change.consume()
                                        velocityDragValue = velocityAt(change.position.y, VELOCITY_LANE_HEIGHT.toPx())
                                    }
                                },
                                onDragEnd = {
                                    val id = velocityDragId
                                    val velocity = velocityDragValue

                                    velocityDragId = NO_NOTE

                                    // One step of undo for the whole drag
                                    if (id != NO_NOTE) {
                                        edit { notes, _ -> ClipEditing.setVelocity(notes, setOf(id), velocity) }
                                    }
                                },
                                onDragCancel = { velocityDragId = NO_NOTE }
                            )
                        }
                ) {
                    val tickPx = barWidth.toPx() / MidiSequencer.TICKS_PER_BAR
                    val barWidthPx = VELOCITY_BAR_WIDTH.toPx()
                    val spacingPx = VELOCITY_BAR_SPACING.toPx()

                    drawRect(color = StudioSurface, size = size)

                    for (guide in VELOCITY_GUIDES) {
                        val y = size.height * (1f - guide)

                        drawLine(
                            color = StudioPanelBorder.copy(alpha = VELOCITY_GUIDE_ALPHA),
                            start = Offset(0f, y),
                            end = Offset(size.width, y)
                        )
                    }

                    for (note in clip.notes) {
                        // What is being dragged shows where the finger is, before it is applied
                        val velocity = if (note.id == velocityDragId) velocityDragValue else note.velocity
                        val x = note.startTick * tickPx + (chordIndexes[note.id] ?: 0) * spacingPx
                        val barHeight = size.height * velocity

                        drawRect(
                            color = if (note.id in selection) TextPrimary else color,
                            topLeft = Offset(x, size.height - barHeight),
                            size = Size(barWidthPx, barHeight)
                        )
                    }
                }
            }
        }

        // What the selected notes share, to show: the chance they are played, and their velocity
        val chances = clip.notes.filter { it.id in selection }.map { it.probability }.toSet()
        val velocities = clip.notes.filter { it.id in selection }.map { (it.velocity * PERCENT).roundToInt() }.toSet()
        val velocityText = when {
            velocities.isEmpty() -> ""
            velocities.size == 1 -> "velocity ${velocities.first()}%"
            else -> "velocity ${velocities.min()}-${velocities.max()}%"
        }

        if (isPanelVisible) {
            RollPanel(
                noteCount = clip.notes.size,
                selectionCount = selection.size,
                velocityText = velocityText,
                selectedChance = if (chances.size == 1) chances.first() else null,
                seed = project.probabilitySeed,
                onDelete = {
                    edit { notes, _ -> ClipEditing.delete(notes, selection) }
                    selectedIds = emptySet()
                },
                onCopy = {
                    // Right after the selection: shifted by its length, up to the next grid line
                    val span = clip.notes.filter { it.id in selection }.let { chosen ->
                        if (chosen.isEmpty()) 0 else chosen.maxOf { it.endTick } - chosen.minOf { it.startTick }
                    }
                    val shift = (span + gridTicks - 1) / gridTicks * gridTicks
                    val copies = ClipEditing.duplicate(clip.notes, selection, shift, clip.lengthTicks)

                    // The copies are the notes with the new ids; they become the selection
                    selectedIds = copies.filter { it.id >= clip.nextNoteId }.map { it.id }.toSet()
                    edit { notes, lengthTicks -> ClipEditing.duplicate(notes, selection, shift, lengthTicks) }
                },
                onQuantize = {
                    edit { notes, lengthTicks -> ClipEditing.quantize(notes, if (selection.isEmpty()) null else selection, gridTicks, lengthTicks) }
                },
                onSelectAll = { selectedIds = clip.notes.map { it.id }.toSet() },
                onSelectNone = { selectedIds = emptySet() },
                onTranspose = { semitones ->
                    edit { notes, lengthTicks -> ClipEditing.move(notes, selection, 0, semitones, lengthTicks) }
                },
                onNudge = { steps ->
                    edit { notes, lengthTicks -> ClipEditing.move(notes, selection, steps * gridTicks, 0, lengthTicks) }
                },
                onLength = { steps ->
                    edit { notes, lengthTicks -> ClipEditing.adjustDuration(notes, selection, steps * gridTicks, lengthTicks) }
                },
                onVelocity = { direction ->
                    edit { notes, _ -> ClipEditing.changeVelocity(notes, selection, direction * VELOCITY_STEP) }
                },
                onChance = { percent ->
                    edit { notes, _ -> ClipEditing.setProbability(notes, selection, percent) }
                },
                onSeed = { newSeed ->
                    arrangement.edit { it.setProbabilitySeed(newSeed) }
                }
            )
        }
    }
}

@Composable
private fun RollHeader(
    title: String,
    canUndo: Boolean,
    canRedo: Boolean,
    isRunning: Boolean,
    canPlay: Boolean,
    isPanelVisible: Boolean,
    onTogglePanel: () -> Unit,
    onBack: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onPlay: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SECTION_SPACING)
    ) {
        RoundButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back to the arrangement",
                tint = TextPrimary,
                modifier = Modifier.size(HEADER_ICON_SIZE)
            )
        }

        Text(
            text = title,
            color = TextPrimary,
            fontSize = HEADER_TITLE_SIZE,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )

        RoundButton(onClick = onTogglePanel) {
            Icon(
                imageVector = if (isPanelVisible) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                contentDescription = if (isPanelVisible) "Hide the tools under the grid" else "Show the tools under the grid",
                tint = TextPrimary,
                modifier = Modifier.size(HEADER_ICON_SIZE)
            )
        }

        if (canPlay) {
            RoundButton(onClick = onPlay, background = if (isRunning) SignalGreen else StudioSurfaceElevated) {
                Icon(
                    imageVector = if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = if (isRunning) "Stop" else "Play the song from this clip",
                    tint = if (isRunning) StudioBackground else TextPrimary,
                    modifier = Modifier.size(HEADER_ICON_SIZE)
                )
            }
        }

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

/** The buttons under the grid: what to do with the notes selected (all the notes, for quantize, if none are). */
@Composable
private fun RollPanel(
    noteCount: Int,
    selectionCount: Int,
    velocityText: String,
    selectedChance: Int?,
    seed: Int,
    onDelete: () -> Unit,
    onCopy: () -> Unit,
    onQuantize: () -> Unit,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
    onTranspose: (Int) -> Unit,
    onNudge: (Int) -> Unit,
    onLength: (Int) -> Unit,
    onVelocity: (Int) -> Unit,
    onChance: (Int) -> Unit,
    onSeed: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(CORNER_RADIUS))
            .background(StudioSurfaceVariant)
            .padding(PANEL_PADDING),
        verticalArrangement = Arrangement.spacedBy(SECTION_SPACING)
    ) {
        Text(
            text = if (selectionCount == 0) {
                "$noteCount notes. Tap the grid to add one, a note to select it, a bar of the velocity lane to set its velocity."
            } else {
                "$selectionCount of $noteCount notes selected${if (velocityText.isEmpty()) "" else ", $velocityText"}. Press a note and hold to move them."
            },
            color = TextPrimary,
            fontSize = BODY_TEXT
        )

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(SECTION_SPACING)
        ) {
            RollButton("Select all", onClick = onSelectAll)
            RollButton("None", onClick = onSelectNone)
            RollButton("Quantize", onClick = onQuantize)
            RollButton("Copy", isEnabled = selectionCount > 0, onClick = onCopy)
            RollButton("Delete", isEnabled = selectionCount > 0, isDanger = true, onClick = onDelete)
        }

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(SECTION_SPACING)
        ) {
            val hasSelection = selectionCount > 0

            RollButton("Pitch -1", isEnabled = hasSelection) { onTranspose(-1) }
            RollButton("Pitch +1", isEnabled = hasSelection) { onTranspose(1) }
            RollButton("Oct -", isEnabled = hasSelection) { onTranspose(-SEMITONES_PER_OCTAVE) }
            RollButton("Oct +", isEnabled = hasSelection) { onTranspose(SEMITONES_PER_OCTAVE) }
            RollButton("< Grid", isEnabled = hasSelection) { onNudge(-1) }
            RollButton("Grid >", isEnabled = hasSelection) { onNudge(1) }
            RollButton("Shorter", isEnabled = hasSelection) { onLength(-1) }
            RollButton("Longer", isEnabled = hasSelection) { onLength(1) }
            RollButton("Soft", isEnabled = hasSelection) { onVelocity(-1) }
            RollButton("Loud", isEnabled = hasSelection) { onVelocity(1) }
        }

        // The chance the selected notes are played each loop, and the seed the rolls follow: the same seed plays
        // the same variation, another seed another one
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SECTION_SPACING)
        ) {
            Text(text = "Chance", color = TextSecondary, fontSize = BODY_TEXT)

            for (level in ClipNote.PROBABILITY_LEVELS) {
                RollButton("$level%", isOn = selectedChance == level, isEnabled = selectionCount > 0) { onChance(level) }
            }

            Text(text = "Seed", color = TextSecondary, fontSize = BODY_TEXT)

            RollButton("-") { onSeed(seed - 1) }

            Text(text = "$seed", color = TextPrimary, fontSize = BUTTON_TEXT, fontWeight = FontWeight.Bold)

            RollButton("+") { onSeed(seed + 1) }
            RollButton("New") { onSeed(Random.nextInt(Project.MAX_PROBABILITY_SEED + 1)) }
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

@Composable
private fun RollButton(
    label: String,
    isOn: Boolean = false,
    isEnabled: Boolean = true,
    isDanger: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .height(BUTTON_HEIGHT)
            .clip(RoundedCornerShape(CORNER_RADIUS))
            .background(if (isOn) SproutGreen else StudioSurfaceElevated)
            .clickable(enabled = isEnabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = when {
                !isEnabled -> TextMuted
                isOn -> StudioBackground
                isDanger -> WarningOrange
                else -> TextPrimary
            },
            fontSize = BUTTON_TEXT,
            fontWeight = FontWeight.Medium
        )
    }
}

/** For each note (by id): how many notes that start at the same tick come before it, lowest pitch first. */
private fun chordIndexesOf(notes: List<ClipNote>): Map<Int, Int> {
    val indexes = HashMap<Int, Int>()

    for (chord in notes.groupBy { it.startTick }.values) {
        chord.sortedBy { it.note }.forEachIndexed { index, note -> indexes[note.id] = index }
    }

    return indexes
}

/**
 * The note whose bar in the velocity lane is nearest to [x], if one is within [radiusPx]. The bars of a chord
 * are spread out, each [spacingPx] after the one before.
 */
private fun velocityBarAt(
    x: Float,
    notes: List<ClipNote>,
    chordIndexes: Map<Int, Int>,
    tickPx: Float,
    spacingPx: Float,
    barWidthPx: Float,
    radiusPx: Float
): ClipNote? {
    var nearest: ClipNote? = null
    var nearestDistance = radiusPx

    for (note in notes) {
        val center = note.startTick * tickPx + (chordIndexes[note.id] ?: 0) * spacingPx + barWidthPx / 2f
        val distance = abs(center - x)

        if (distance <= nearestDistance) {
            nearest = note
            nearestDistance = distance
        }
    }

    return nearest
}

/** The velocity a touch at [y] of a lane [heightPx] tall stands for: the top is 100%, in whole percents. */
private fun velocityAt(y: Float, heightPx: Float): Float {
    return ((1f - y / heightPx).coerceIn(0f, 1f) * PERCENT).roundToInt() / PERCENT.toFloat()
}
