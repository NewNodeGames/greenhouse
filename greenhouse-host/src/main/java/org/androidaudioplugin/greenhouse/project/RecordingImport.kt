package org.androidaudioplugin.greenhouse.project

import org.androidaudioplugin.greenhouse.core.MidiSequencer
import org.androidaudioplugin.greenhouse.ui.host.SequenceNote

/**
 * What was just recorded, as clips of the arrangement. Each track that was played gets one clip, from
 * the bar of its first note to the bar its last note ends in, placed where it was played and over
 * whatever was placed there (the recording replaces it; the project's undo brings it back).
 */
object RecordingImport {
    private const val CLIP_NAME_PREFIX = "Recording"

    /**
     * [notes] are the recorded notes, at their ticks in the song. [loopBars] is the loop the take was
     * recorded over: the first recording into an empty song sets the song's length to it, as the first
     * take of the sequencer always has. Notes on a track the project lacks are dropped.
     */
    fun addRecording(project: Project, notes: List<SequenceNote>, loopBars: Int): Project {
        val isFirstRecording = !project.hasPlacements
        var result = project

        for ((trackIndex, trackNotes) in notes.groupBy { it.slot }.toSortedMap()) {
            if (trackIndex !in result.tracks.indices) {
                continue
            }

            result = addTrackRecording(result, trackIndex, trackNotes)
        }

        if (isFirstRecording && result.hasPlacements) {
            result = result.setMinLengthBars(loopBars)
        }

        return result
    }

    private fun addTrackRecording(project: Project, trackIndex: Int, notes: List<SequenceNote>): Project {
        val barTicks = MidiSequencer.TICKS_PER_BAR.toLong()
        val firstTick = notes.minOf { it.startTick }
        val lastTick = notes.maxOf { it.endTick }
        val startBar = (firstTick / barTicks).toInt()
        val endBar = maxOf(startBar + 1, ((lastTick + barTicks - 1) / barTicks).toInt())
        val clipNotes = notes.sortedBy { it.startTick }.mapIndexed { index, note ->
            ClipNote(
                id = index + 1,
                note = note.note,
                startTick = (note.startTick - startBar * barTicks).toInt(),
                durationTicks = (note.endTick - note.startTick).toInt().coerceAtLeast(ClipNote.MIN_DURATION_TICKS),
                velocity = note.velocity
            )
        }
        val name = "$CLIP_NAME_PREFIX ${project.tracks[trackIndex].nextClipId}"

        return project.addRecordedClip(trackIndex, startBar, endBar - startBar, clipNotes, name)?.sanitized() ?: project
    }
}
