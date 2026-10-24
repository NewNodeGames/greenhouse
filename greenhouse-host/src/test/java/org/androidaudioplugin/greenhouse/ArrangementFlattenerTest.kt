package org.androidaudioplugin.greenhouse

import org.androidaudioplugin.greenhouse.core.MidiSequencer
import org.androidaudioplugin.greenhouse.project.ArrangementFlattener
import org.androidaudioplugin.greenhouse.project.Clip
import org.androidaudioplugin.greenhouse.project.ClipNote
import org.androidaudioplugin.greenhouse.project.Placement
import org.androidaudioplugin.greenhouse.project.Project
import org.androidaudioplugin.greenhouse.project.Track
import org.androidaudioplugin.greenhouse.project.addClip
import org.androidaudioplugin.greenhouse.project.addTrack
import org.androidaudioplugin.greenhouse.project.editNotes
import org.androidaudioplugin.greenhouse.project.placeClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrangementFlattenerTest {

    companion object {
        private const val BAR = MidiSequencer.TICKS_PER_BAR
        private const val BEAT = MidiSequencer.PPQ
        private const val MIDI2_NOTE_ON = 0x40900000
        private const val MIDI2_NOTE_OFF = 0x40800000
        private const val NOTE_SHIFT = 8
        private const val UMP_STATUS_SHIFT = 20
        private const val UMP_NIBBLE_MASK = 0xF
        private const val UMP_DATA7_MASK = 0x7F
        private const val STATUS_NOTE_ON = 0x9
        private const val STATUS_NOTE_OFF = 0x8
        private const val WORD_COUNT_INDEX = 3
        private const val FULL_VELOCITY16 = 0xFFFF0000.toInt()
        private const val HALF_VELOCITY16 = 0x80000000.toInt()
        private const val LOWEST_VELOCITY16 = 0x00010000
        private const val PROBABILITY_SET_FLAG = 0x80000000.toInt()
        private const val PROBABILITY_WORD = 2
    }

    /** An event of the flattened result, read back. */
    private data class Played(val tick: Int, val track: Int, val isNoteOn: Boolean, val note: Int)

    private fun read(result: ArrangementFlattener.Result): List<Played> {
        val packed = result.packedEvents
        val played = ArrayList<Played>()

        for (base in 0 until packed.size step MidiSequencer.EVENT_STRIDE) {
            val word0 = packed[base + MidiSequencer.EVENT_WORDS]
            val status = (word0 ushr UMP_STATUS_SHIFT) and UMP_NIBBLE_MASK

            played.add(
                Played(
                    tick = packed[base + MidiSequencer.EVENT_TICK],
                    track = packed[base + MidiSequencer.EVENT_SLOT],
                    isNoteOn = status == STATUS_NOTE_ON,
                    note = (word0 ushr NOTE_SHIFT) and UMP_DATA7_MASK
                )
            )

            assertTrue(status == STATUS_NOTE_ON || status == STATUS_NOTE_OFF)
        }

        return played
    }

    /** A project whose first track holds the clip 1, [lengthBars] long, with these notes (id, pitch, start, duration). */
    private fun projectWith(lengthBars: Int, vararg notes: ClipNote): Project {
        return Project().addClip(0, lengthBars).editNotes(0, 1) { existing, _ -> existing + notes }
    }

    @Test
    fun aNoteBecomesANoteOnAndANoteOffInEnginePackedFormat() {
        val project = projectWith(1, ClipNote(1, 60, 0, BEAT, 1f)).placeClip(0, 1, 2)!!

        val result = ArrangementFlattener.flatten(project)
        val packed = result.packedEvents

        assertEquals(2 * MidiSequencer.EVENT_STRIDE, packed.size)
        assertEquals(2, result.eventCount)
        assertFalse(result.exceedsEngineLimit)
        // The song is at least four bars: the last placement ends at bar 3
        assertEquals(4, result.lengthBars)

        // tick, track, take, UMP word count, then the two UMP words of a MIDI 2.0 note and two unused
        assertEquals(listOf(2 * BAR, 0, 0, 2), packed.slice(0..3))
        assertEquals(MIDI2_NOTE_ON or (60 shl NOTE_SHIFT), packed[MidiSequencer.EVENT_WORDS])
        assertEquals(FULL_VELOCITY16, packed[MidiSequencer.EVENT_WORDS + 1])

        val off = MidiSequencer.EVENT_STRIDE
        assertEquals(listOf(2 * BAR + BEAT, 0, 0, 2), packed.slice(off..off + WORD_COUNT_INDEX))
        assertEquals(MIDI2_NOTE_OFF or (60 shl NOTE_SHIFT), packed[off + MidiSequencer.EVENT_WORDS])
        assertEquals(0, packed[off + MidiSequencer.EVENT_WORDS + 1])
    }

    @Test
    fun velocityIsSixteenBitAndNeverZero() {
        val project = projectWith(
            1,
            ClipNote(1, 60, 0, 100, 0.5f),
            ClipNote(2, 61, 200, 100, 0f)
        ).placeClip(0, 1, 0)!!

        val packed = ArrangementFlattener.flatten(project).packedEvents
        val stride = MidiSequencer.EVENT_STRIDE

        // Events in time order: on 60, off 60, on 61, off 61
        assertEquals(HALF_VELOCITY16, packed[MidiSequencer.EVENT_WORDS + 1])
        // A note-on with velocity 0 would be a note-off
        assertEquals(LOWEST_VELOCITY16, packed[2 * stride + MidiSequencer.EVENT_WORDS + 1])
    }

    @Test
    fun eachTrackPlaysOnItsOwnSlotAndEventsAreInTimeOrder() {
        var project = Project().addTrack().addClip(0, 1).addClip(1, 1)
        project = project.editNotes(0, 1) { notes, _ -> notes + ClipNote(1, 60, BEAT, 100) }
        project = project.editNotes(1, 1) { notes, _ -> notes + ClipNote(1, 40, 0, 100) + ClipNote(2, 43, BEAT, 100) }
        project = project.placeClip(0, 1, 0)!!.placeClip(1, 1, 0)!!

        val played = read(ArrangementFlattener.flatten(project))

        assertEquals(
            listOf(
                Played(0, 1, true, 40),
                Played(100, 1, false, 40),
                // The same tick: the lower track first
                Played(BEAT, 0, true, 60),
                Played(BEAT, 1, true, 43),
                Played(BEAT + 100, 0, false, 60),
                Played(BEAT + 100, 1, false, 43)
            ),
            played
        )
    }

    @Test
    fun aNoteIsCutWhereItsClipEnds() {
        val project = projectWith(1, ClipNote(1, 60, 3000, 2000)).placeClip(0, 1, 1)!!

        val played = read(ArrangementFlattener.flatten(project))

        assertEquals(listOf(Played(BAR + 3000, 0, true, 60), Played(2 * BAR, 0, false, 60)), played)
    }

    @Test
    fun aNoteEndsWhereTheNextOneOfItsPitchStarts() {
        val project = projectWith(
            1,
            ClipNote(1, 60, 0, 2000),
            ClipNote(2, 60, 1000, 500)
        ).placeClip(0, 1, 0)!!

        val played = read(ArrangementFlattener.flatten(project))

        // The first note is cut at 1000, and its end comes before the start of the second
        assertEquals(
            listOf(
                Played(0, 0, true, 60),
                Played(1000, 0, false, 60),
                Played(1000, 0, true, 60),
                Played(1500, 0, false, 60)
            ),
            played
        )
    }

    @Test
    fun aNoteRepeatedAtTheSameTickPlaysOnce() {
        val project = projectWith(1, ClipNote(1, 60, 0, 500), ClipNote(2, 60, 0, 900)).placeClip(0, 1, 0)!!

        val result = ArrangementFlattener.flatten(project)

        assertEquals(2, result.eventCount)
    }

    @Test
    fun aClipPlacedTwiceFlattensTwiceAndBackToBackNotesAreStruckAgain() {
        // A note as long as the clip, which starts again as its next placement begins
        val project = projectWith(1, ClipNote(1, 60, 0, BAR)).placeClip(0, 1, 0)!!.placeClip(0, 1, 1)!!

        val played = read(ArrangementFlattener.flatten(project))

        assertEquals(
            listOf(
                Played(0, 0, true, 60),
                Played(BAR, 0, false, 60),
                Played(BAR, 0, true, 60),
                Played(2 * BAR, 0, false, 60)
            ),
            played
        )
    }

    @Test
    fun anEmptyProjectFlattensToNothing() {
        val result = ArrangementFlattener.flatten(Project())

        assertEquals(0, result.packedEvents.size)
        assertEquals(0, result.eventCount)
        assertEquals(Project.DEFAULT_LENGTH_BARS, result.lengthBars)
    }

    @Test
    fun aPlacementOfAMissingClipIsIgnored() {
        val track = Track("T", emptyList(), listOf(Placement(1, 7, 0)))

        val result = ArrangementFlattener.flatten(Project(listOf(track), 4))

        assertEquals(0, result.eventCount)
    }

    @Test
    fun warnsWhenTheSongHasMoreEventsThanTheEngineHolds() {
        // Distinct pitch and start, one tick long: 33000 notes are 66000 events
        val notes = List(33000) { ClipNote(it + 1, it % 128, it / 128, 1) }
        val track = Track("T", listOf(Clip(1, "Big", 4, notes)), listOf(Placement(1, 1, 0)))

        val result = ArrangementFlattener.flatten(Project(listOf(track), 4))

        assertEquals(66000, result.eventCount)
        assertTrue(result.exceedsEngineLimit)
    }

    @Test
    fun aChanceBelowOneHundredIsKeptInTheThirdWordOfTheNoteOn() {
        val project = projectWith(
            1,
            ClipNote(1, 60, 0, 100, 1f, 25),
            ClipNote(2, 62, 200, 100, 1f, 0),
            ClipNote(3, 64, 400, 100, 1f)
        ).placeClip(0, 1, 0)!!

        val packed = ArrangementFlattener.flatten(project).packedEvents
        val stride = MidiSequencer.EVENT_STRIDE
        val words = MidiSequencer.EVENT_WORDS + PROBABILITY_WORD

        // In time order: on and off of 60, then of 62, then of 64
        assertEquals(PROBABILITY_SET_FLAG or 25, packed[0 * stride + words])
        // The note-off carries nothing: the engine drops it with the note that was not played
        assertEquals(0, packed[1 * stride + words])
        // Zero percent is a setting too, which the flag tells from nothing
        assertEquals(PROBABILITY_SET_FLAG, packed[2 * stride + words])
        // Always played is what an unset word means
        assertEquals(0, packed[4 * stride + words])
    }
}
