package org.androidaudioplugin.greenhouse.ui.host

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.androidaudioplugin.greenhouse.core.MidiSequencer
import org.androidaudioplugin.greenhouse.data.SequenceState
import org.androidaudioplugin.greenhouse.data.SlotHostSettings
import org.androidaudioplugin.greenhouse.project.ArrangementFlattener
import org.androidaudioplugin.greenhouse.project.LegacySequenceImport
import org.androidaudioplugin.greenhouse.project.Project
import org.androidaudioplugin.greenhouse.project.RecordingImport
import org.androidaudioplugin.greenhouse.project.Track
import org.androidaudioplugin.greenhouse.project.clearSong
import org.androidaudioplugin.greenhouse.project.sanitized
import org.androidaudioplugin.greenhouse.project.setMinLengthBars
import org.androidaudioplugin.greenhouse.project.updateTrack
import org.androidaudioplugin.greenhouse.project.withMixOf
import org.androidaudioplugin.greenhouse.project.withTrackCount

/**
 * The arrangement the session plays: the [Project], its undo history, and what it takes to make the
 * engine play it. The project is the source of truth: after every change it is flattened to the events
 * of the engine's sequencer and swapped in while the transport keeps running, and the mix settings of
 * its tracks are set on the engine's tracks. Main thread only.
 *
 * What is recorded lands in the engine as takes; [commitRecording] turns them into clips of the project.
 */
class ProjectController(
    private val audio: AudioEngineController,
    private val postStatus: (String) -> Unit
) {
    companion object {
        private const val TAG = "ProjectController"
        private const val MAX_UNDO_STEPS = 50
        private const val NEUTRAL_TRACK_GAIN = 1f
    }

    private val engine = audio.engine
    private val sequencer = engine.sequencer
    private val undoStack = ArrayDeque<Project>()
    private val redoStack = ArrayDeque<Project>()

    /** The engine could not take the last flattened song (it was recording): it is sent when that ends. */
    private var isApplyPending = false

    // What the engine was last given, so a change that leaves the events alone (a track's level) does not
    // send them again: replacing the events ends the notes that are sounding
    private var sentEvents: IntArray? = null
    private var sentLengthBars = 0

    /** The project always has the tracks of the rack, at least. */
    var project by mutableStateOf(Project().withTrackCount(engine.numTracks))
        private set

    /** Bumped by every change to the project, for what needs to know it changed rather than how. */
    var revision by mutableIntStateOf(0)
        private set

    var canUndo by mutableStateOf(false)
        private set

    var canRedo by mutableStateOf(false)
        private set

    init {
        // The engine starts with an empty sequence: nothing to send until the project has something
        sequencer.setProbabilitySeed(project.probabilitySeed)
        applyTrackMix()
    }

    /** Tells the user something about an edit that was refused (see [edit]). */
    fun report(message: String) {
        postStatus(message)
    }

    /**
     * Changes the project: [transform] gets it and returns the changed one, or null to refuse (see
     * `placeClip()`), which changes nothing and returns false. A change can be undone.
     */
    fun edit(transform: (Project) -> Project?): Boolean {
        val changed = transform(project) ?: return false

        if (changed == project) {
            return true
        }

        undoStack.addLast(project)

        if (undoStack.size > MAX_UNDO_STEPS) {
            undoStack.removeFirst()
        }

        redoStack.clear()
        adopt(changed)
        return true
    }

    /**
     * Changes the level, pan, mute or solo of a track ([transform] gets the track). These are not steps of the
     * history: the mix is not what is undone, and moving a fader would bury the changes to the song.
     */
    fun updateTrackMix(trackIndex: Int, transform: (Track) -> Track) {
        project = project.updateTrack(trackIndex, transform)
        revision++
        applyTrackMix()
    }

    /** Goes back to the project before the last change, with the mix as it is. Returns false if there is nothing to undo. */
    fun undo(): Boolean {
        val previous = undoStack.removeLastOrNull() ?: return false

        redoStack.addLast(project)
        adopt(previous.withMixOf(project))
        return true
    }

    /** Redoes the last change undone, with the mix as it is. Returns false if there is nothing to redo. */
    fun redo(): Boolean {
        val next = redoStack.removeLastOrNull() ?: return false

        undoStack.addLast(project)
        adopt(next.withMixOf(project))
        return true
    }

    /** Replaces the project, e.g. with the one of a session just loaded; the history starts over. */
    fun load(newProject: Project) {
        undoStack.clear()
        redoStack.clear()
        // The sequencer was just restored from the session: the engine holds whatever that put there
        adopt(newProject.sanitized(), force = true)
    }

    fun clearSong() {
        edit { it.clearSong() }
    }

    /**
     * Sets the length of the song to at least [bars]; [MidiSequencer.AUTO_LENGTH_BARS] counts as the
     * shortest. The song is never shorter than its last clip.
     */
    fun setLengthBars(bars: Int) {
        edit { it.setMinLengthBars(bars) }
    }

    /**
     * Makes the project from the engine's sequence, after the sequencer replaced it on its own (a MIDI
     * file was imported). The tracks keep their mix settings.
     */
    fun adoptEngineSequence() {
        val sequence = SequenceState(sequencer.settings, SequenceState.encodeEvents(sequencer.getEvents()), sequencer.status.autoLengthBars)
        val imported = LegacySequenceImport.fromSequence(sequence)
        val previousTracks = project.tracks

        edit {
            imported.copy(
                probabilitySeed = project.probabilitySeed,
                tracks = imported.tracks.mapIndexed { index, track ->
                    val previous = previousTracks.getOrNull(index)

                    if (previous == null) {
                        track
                    } else {
                        track.copy(levelDb = previous.levelDb, pan = previous.pan, isMuted = previous.isMuted, isSoloed = previous.isSoloed)
                    }
                }
            )
        }
    }

    /**
     * Turns the takes the engine recorded into clips of the project (see [RecordingImport]) and sends the
     * result back, which leaves the engine with the song only. Does nothing while recording goes on.
     */
    fun commitRecording() {
        val status = sequencer.status

        if (status.isRecording) {
            return
        }

        val recorded = recordedEvents(sequencer.getEvents())

        if (recorded.isEmpty()) {
            if (isApplyPending) {
                applyToEngine()
            }

            return
        }

        val loopTicks = status.lengthTicks
        val loopBars = (loopTicks / MidiSequencer.TICKS_PER_BAR).toInt().coerceAtLeast(1)

        edit { RecordingImport.addRecording(it, SequencerController.extractNotes(recorded, loopTicks), loopBars) }
        // Whether or not the takes changed the project, the engine still holds them: send the song, which has none
        applyToEngine(force = true)
    }

    private fun adopt(newProject: Project, force: Boolean = false) {
        project = newProject.withTrackCount(engine.numTracks)
        revision++
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
        applyToEngine(force)
    }

    /** The events of the takes: the ones that are not part of the song, which has none. */
    private fun recordedEvents(packed: IntArray): IntArray {
        val stride = MidiSequencer.EVENT_STRIDE
        val recorded = ArrayList<Int>()

        for (base in 0..packed.size - stride step stride) {
            if (packed[base + MidiSequencer.EVENT_TAKE] > 0) {
                for (offset in 0 until stride) {
                    recorded.add(packed[base + offset])
                }
            }
        }

        return recorded.toIntArray()
    }

    /**
     * Sends the project to the engine: its length when it has a song, the flattened events, and the mix
     * settings of the tracks. An empty song leaves the length to the sequencer's setting, which can be
     * automatic: the first take sets it. The events are only sent when they differ from the ones last
     * sent, or when [force] says the engine's are not those (see [load] and [commitRecording]).
     */
    private fun applyToEngine(force: Boolean = false) {
        val flat = ArrangementFlattener.flatten(project)
        val settings = sequencer.settings

        if (project.hasPlacements && settings.lengthBars != flat.lengthBars) {
            sequencer.settings = settings.copy(lengthBars = flat.lengthBars)
        }

        val isSent = !force && !isApplyPending && sentLengthBars == flat.lengthBars &&
            sentEvents?.contentEquals(flat.packedEvents) == true

        if (!isSent) {
            isApplyPending = !sequencer.replaceEvents(flat.packedEvents, flat.lengthBars)

            if (isApplyPending) {
                Log.w(TAG, "The engine is recording: the song is sent when it stops")
            } else {
                sentEvents = flat.packedEvents
                sentLengthBars = flat.lengthBars
            }
        }

        if (flat.exceedsEngineLimit) {
            postStatus("The song has more notes than the engine can play: the end is cut.")
        }

        // A seed is not an event: changing it takes effect at once, without sending the song again
        sequencer.setProbabilitySeed(project.probabilitySeed)
        applyTrackMix()
    }

    private fun applyTrackMix() {
        for (index in 0 until engine.numTracks) {
            val track = project.tracks.getOrNull(index)

            if (track == null) {
                engine.setTrackGain(index, NEUTRAL_TRACK_GAIN)
                engine.setTrackPan(index, Track.CENTER_PAN)
                engine.setTrackMuted(index, false)
                engine.setTrackSoloed(index, false)
            } else {
                engine.setTrackGain(index, SlotHostSettings.levelDbToGain(track.levelDb))
                engine.setTrackPan(index, track.pan)
                engine.setTrackMuted(index, track.isMuted)
                engine.setTrackSoloed(index, track.isSoloed)
            }
        }
    }
}
