package org.androidaudioplugin.greenhouse

import org.androidaudioplugin.greenhouse.core.MidiSequencer
import org.androidaudioplugin.greenhouse.project.Clip
import org.androidaudioplugin.greenhouse.project.ClipEditing
import org.androidaudioplugin.greenhouse.project.ClipNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipEditingTest {

    companion object {
        private const val BAR = MidiSequencer.TICKS_PER_BAR
        private const val BEAT = MidiSequencer.PPQ
        private const val GRID = 240
        private const val MIDDLE_C = 60
        private const val VELOCITY_TOLERANCE = 0.0001f
    }

    private fun note(id: Int, pitch: Int = MIDDLE_C, start: Int = 0, duration: Int = BEAT): ClipNote {
        return ClipNote(id, pitch, start, duration)
    }

    @Test
    fun addedNoteGetsTheNextIdAndStaysInTheClip() {
        val notes = listOf(note(1), note(5))

        val added = ClipEditing.addNote(notes, 200, BAR + 100, BEAT, 2f, BAR)

        assertEquals(3, added.size)
        val last = added.last()
        assertEquals(6, last.id)
        assertEquals(ClipNote.MAX_NOTE, last.note)
        // Started past the end: it takes the last tick and lasts the one tick left
        assertEquals(BAR - 1, last.startTick)
        assertEquals(1, last.durationTicks)
        assertEquals(1f, last.velocity, VELOCITY_TOLERANCE)
    }

    @Test
    fun selectedNotesMoveAsAGroupAndStopAtTheEdges() {
        val notes = listOf(
            note(1, pitch = 60, start = 100, duration = 500),
            note(2, pitch = 64, start = 3000, duration = 500),
            note(3, pitch = 70, start = 1000)
        )
        val selection = setOf(1, 2)

        // The second note could go 340 ticks ahead: both move 340, and note 3 does not move
        val later = ClipEditing.move(notes, selection, 1000, 0, BAR)
        assertEquals(listOf(440, 3340, 1000), later.map { it.startTick })

        // The first note could only go 100 back
        val earlier = ClipEditing.move(notes, selection, -1000, 0, BAR)
        assertEquals(listOf(0, 2900, 1000), earlier.map { it.startTick })

        val higher = ClipEditing.move(notes, selection, 0, 100, BAR)
        assertEquals(listOf(60 + 63, 64 + 63, 70), higher.map { it.note })

        val lower = ClipEditing.move(notes, selection, 0, -100, BAR)
        assertEquals(listOf(0, 4, 70), lower.map { it.note })
    }

    @Test
    fun movingNothingChangesNothing() {
        val notes = listOf(note(1))

        assertEquals(notes, ClipEditing.move(notes, emptySet(), BEAT, 3, BAR))
        assertEquals(notes, ClipEditing.move(notes, setOf(99), BEAT, 3, BAR))
    }

    @Test
    fun resizeKeepsTheStartAndTheClipEnd() {
        val notes = listOf(note(1, start = 3000), note(2, start = 0))

        assertEquals(840, ClipEditing.resize(notes, 1, 5000, BAR)[0].durationTicks)
        assertEquals(1, ClipEditing.resize(notes, 1, 0, BAR)[0].durationTicks)
        assertEquals(2000, ClipEditing.resize(notes, 2, 2000, BAR)[1].durationTicks)
        assertEquals(notes, ClipEditing.resize(notes, 99, 2000, BAR))
    }

    @Test
    fun deleteAndVelocityAffectOnlyTheSelection() {
        val notes = listOf(note(1), note(2), note(3))

        assertEquals(listOf(2), ClipEditing.delete(notes, setOf(1, 3)).map { it.id })

        val softer = ClipEditing.setVelocity(notes, setOf(2), 0.25f)
        assertEquals(listOf(ClipNote.DEFAULT_VELOCITY, 0.25f, ClipNote.DEFAULT_VELOCITY), softer.map { it.velocity })
        assertEquals(1f, ClipEditing.setVelocity(notes, setOf(1), 5f)[0].velocity, VELOCITY_TOLERANCE)
    }

    @Test
    fun quantizeSnapsStartsToTheNearestLine() {
        val notes = listOf(
            note(1, start = 100),
            note(2, start = 130),
            note(3, start = 120, duration = 77),
            note(4, start = BAR - 10, duration = 100)
        )

        val all = ClipEditing.quantize(notes, null, GRID, BAR)
        // 120 is halfway: it snaps up. The last one snaps onto the clip end, which is out: it takes the last tick
        assertEquals(listOf(0, GRID, GRID, BAR - 1), all.map { it.startTick })
        assertEquals(listOf(BEAT, BEAT, 77, 1), all.map { it.durationTicks })

        val some = ClipEditing.quantize(notes, setOf(2), GRID, BAR)
        assertEquals(listOf(100, GRID, 120, BAR - 10), some.map { it.startTick })

        assertEquals(notes, ClipEditing.quantize(notes, null, 0, BAR))
    }

    @Test
    fun rectangleSelectsTheNotesThatTouchIt() {
        val notes = listOf(
            note(1, pitch = 60, start = 0, duration = 500),
            note(2, pitch = 62, start = 500, duration = 500),
            note(3, pitch = 72, start = 600, duration = 500),
            note(4, pitch = 61, start = 2000, duration = 500)
        )

        // Time 400 to 700, pitch 60 to 65: note 1 ends after the left edge, note 2 starts before the right one
        assertEquals(setOf(1, 2), ClipEditing.notesIn(notes, 400, 700, 60, 65))
        // A note that ends exactly where the rectangle starts is not touched
        assertEquals(setOf(2), ClipEditing.notesIn(notes, 500, 700, 60, 65))
        assertTrue(ClipEditing.notesIn(notes, 0, BAR, 0, 10).isEmpty())
    }

    @Test
    fun shorteningAClipDropsAndCutsNotes() {
        val clip = Clip(
            1, "A", 2,
            listOf(
                note(1, start = 0),
                note(2, start = 3000, duration = 2000),
                note(3, start = BAR + 10)
            )
        )

        val shorter = ClipEditing.fitToLength(clip, 1)

        assertEquals(1, shorter.lengthBars)
        assertEquals(listOf(1, 2), shorter.notes.map { it.id })
        // Cut where the clip now ends
        assertEquals(840, shorter.notes[1].durationTicks)

        val longer = ClipEditing.fitToLength(clip, 4)
        assertEquals(clip.notes, longer.notes)
        assertEquals(4, longer.lengthBars)
    }
}
