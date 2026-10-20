package org.androidaudioplugin.greenhouse

import org.androidaudioplugin.greenhouse.core.MidiSequencer
import org.androidaudioplugin.greenhouse.project.Project
import org.androidaudioplugin.greenhouse.project.RecordingImport
import org.androidaudioplugin.greenhouse.project.addTrack
import org.androidaudioplugin.greenhouse.project.addClip
import org.androidaudioplugin.greenhouse.project.placeClip
import org.androidaudioplugin.greenhouse.ui.host.SequenceNote
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingImportTest {

    companion object {
        private const val BAR = MidiSequencer.TICKS_PER_BAR
        private const val BAR_TICKS = BAR.toLong()
    }

    private fun note(slot: Int, pitch: Int, start: Long, end: Long): SequenceNote {
        return SequenceNote(slot, pitch, start, end, take = 1)
    }

    @Test
    fun theFirstRecordingBecomesAClipAndSetsTheSongLength() {
        val notes = listOf(note(0, 60, 100, 1000), note(0, 62, 2000, 3000))

        val project = RecordingImport.addRecording(Project(), notes, 2)

        val track = project.tracks[0]
        assertEquals(1, track.clips.size)
        assertEquals("Recording 1", track.clips[0].name)
        // Both notes are in the first bar
        assertEquals(1, track.clips[0].lengthBars)
        assertEquals(listOf(0), track.placements.map { it.startBar })
        assertEquals(listOf(100, 2000), track.clips[0].notes.map { it.startTick })
        assertEquals(listOf(900, 1000), track.clips[0].notes.map { it.durationTicks })
        // The loop the take was recorded over is the song, as the sequencer's first take always was
        assertEquals(2, project.minLengthBars)
        assertEquals(2, project.lengthBars)
    }

    @Test
    fun aLaterRecordingIsPlacedWhereItWasPlayedOverWhatIsThere() {
        val existing = Project().addClip(0, 2, "Old").placeClip(0, 1, 0)!!
        val notes = listOf(note(0, 60, BAR_TICKS + 500, BAR_TICKS + 900))

        val project = RecordingImport.addRecording(existing, notes, 8)

        val track = project.tracks[0]
        // The recording is in the second bar: the old clip, bars 1 and 2, gives way
        assertEquals(listOf(1), track.placements.map { it.startBar })
        assertEquals(listOf(2), track.placements.map { it.clipId })
        assertEquals(1, track.clips[1].lengthBars)
        assertEquals(listOf(500), track.clips[1].notes.map { it.startTick })
        assertEquals(2, track.clips.size)
        // The song already had a length: the loop does not change it
        assertEquals(existing.minLengthBars, project.minLengthBars)
    }

    @Test
    fun aNoteAcrossABarLineMakesTheClipLongerAndEachTrackGetsItsOwn() {
        val notes = listOf(note(0, 60, 3800, 4000), note(1, 40, 0, 500))

        val project = RecordingImport.addRecording(Project().addTrack(), notes, 4)

        assertEquals(2, project.tracks[0].clips[0].lengthBars)
        assertEquals(1, project.tracks[1].clips[0].lengthBars)
        assertEquals(listOf(40), project.tracks[1].clips[0].notes.map { it.note })
    }

    @Test
    fun notesOnATrackTheProjectLacksAreDropped() {
        val project = Project()

        val result = RecordingImport.addRecording(project, listOf(note(5, 60, 0, 100)), 2)

        assertEquals(project, result)
    }
}
