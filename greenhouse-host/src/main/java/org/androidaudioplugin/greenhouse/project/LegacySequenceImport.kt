package org.androidaudioplugin.greenhouse.project

import org.androidaudioplugin.greenhouse.core.MidiSequencer
import org.androidaudioplugin.greenhouse.data.RackPreset
import org.androidaudioplugin.greenhouse.data.SequenceState
import org.androidaudioplugin.greenhouse.ui.host.SequencerController

/**
 * Sessions saved before the arrangement hold one looping sequence. It becomes a project in which each
 * track that played notes has one clip, as long as the loop, placed at the first bar.
 */
object LegacySequenceImport {
    private const val FIRST_CLIP_ID = 1
    private const val FIRST_PLACEMENT_ID = 1
    private const val LEGACY_CLIP_NAME = "Sequence"

    fun fromSequence(sequence: SequenceState?): Project {
        if (sequence == null) {
            return Project()
        }

        val packed = SequenceState.decodeEvents(sequence.eventsBase64)

        if (packed.isEmpty()) {
            return Project()
        }

        val lengthBars = loopLengthBars(sequence, packed)
        val lengthTicks = lengthBars.toLong() * MidiSequencer.TICKS_PER_BAR
        // As recorded: the sequencer's quantize setting still applies when the song plays
        val notesByTrack = SequencerController.extractNotes(packed, lengthTicks)
            .filter { it.slot in 0 until Project.MAX_TRACKS }
            .groupBy { it.slot }
        val trackCount = (notesByTrack.keys.maxOrNull() ?: 0) + 1
        val tracks = ArrayList<Track>()

        for (trackIndex in 0 until trackCount) {
            val name = "Track ${trackIndex + 1}"
            val notes = notesByTrack[trackIndex].orEmpty().mapIndexed { index, note ->
                ClipNote(
                    id = index + 1,
                    note = note.note,
                    startTick = note.startTick.toInt(),
                    durationTicks = (note.endTick - note.startTick).toInt().coerceAtLeast(ClipNote.MIN_DURATION_TICKS),
                    velocity = note.velocity
                )
            }

            if (notes.isEmpty()) {
                tracks.add(Track(name))
            } else {
                val clip = Clip(FIRST_CLIP_ID, LEGACY_CLIP_NAME, lengthBars, notes)
                tracks.add(Track(name, listOf(clip), listOf(Placement(FIRST_PLACEMENT_ID, FIRST_CLIP_ID, 0))))
            }
        }

        return Project(tracks, lengthBars).sanitized()
    }

    /** The loop length: the setting, or what the first take set, or enough bars for the last event. */
    private fun loopLengthBars(sequence: SequenceState, packed: IntArray): Int {
        val fixedBars = sequence.settings.lengthBars

        if (fixedBars != MidiSequencer.AUTO_LENGTH_BARS) {
            return fixedBars.coerceIn(Project.MIN_LENGTH_BARS, Project.MAX_LENGTH_BARS)
        }

        if (sequence.autoLengthBars > 0) {
            return sequence.autoLengthBars.coerceIn(Project.MIN_LENGTH_BARS, Project.MAX_LENGTH_BARS)
        }

        var lastTick = 0

        for (index in 0..packed.size - MidiSequencer.EVENT_STRIDE step MidiSequencer.EVENT_STRIDE) {
            lastTick = maxOf(lastTick, packed[index + MidiSequencer.EVENT_TICK])
        }

        return (lastTick / MidiSequencer.TICKS_PER_BAR + 1).coerceIn(Project.MIN_LENGTH_BARS, Project.MAX_LENGTH_BARS)
    }
}

/** The project of a session: the saved one, or the one made from the sequence of a session that has none. */
fun RackPreset.resolveProject(): Project {
    return project ?: LegacySequenceImport.fromSequence(sequence)
}
