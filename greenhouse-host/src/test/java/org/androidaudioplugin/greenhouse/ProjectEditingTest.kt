package org.androidaudioplugin.greenhouse

import org.androidaudioplugin.greenhouse.core.MidiSequencer
import org.androidaudioplugin.greenhouse.project.Clip
import org.androidaudioplugin.greenhouse.project.ClipNote
import org.androidaudioplugin.greenhouse.project.Placement
import org.androidaudioplugin.greenhouse.project.Project
import org.androidaudioplugin.greenhouse.project.Track
import org.androidaudioplugin.greenhouse.project.addClip
import org.androidaudioplugin.greenhouse.project.addRecordedClip
import org.androidaudioplugin.greenhouse.project.addTrack
import org.androidaudioplugin.greenhouse.project.clearSong
import org.androidaudioplugin.greenhouse.project.duplicateClip
import org.androidaudioplugin.greenhouse.project.duplicatePlacement
import org.androidaudioplugin.greenhouse.project.editNotes
import org.androidaudioplugin.greenhouse.project.movePlacement
import org.androidaudioplugin.greenhouse.project.placeClip
import org.androidaudioplugin.greenhouse.project.placeClipReplacing
import org.androidaudioplugin.greenhouse.project.removeClip
import org.androidaudioplugin.greenhouse.project.removePlacement
import org.androidaudioplugin.greenhouse.project.removeTrack
import org.androidaudioplugin.greenhouse.project.sanitized
import org.androidaudioplugin.greenhouse.project.setClipLength
import org.androidaudioplugin.greenhouse.project.setMinLengthBars
import org.androidaudioplugin.greenhouse.project.updateTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectEditingTest {

    /** A project with one track holding the clip "Riff" (id 1), not placed yet. */
    private fun projectWithClip(lengthBars: Int): Project {
        return Project().addClip(0, lengthBars, "Riff")
    }

    @Test
    fun tracksAreNumberedAndLimited() {
        var project = Project().addTrack().addTrack("Bass")

        assertEquals(listOf("Track 1", "Track 2", "Bass"), project.tracks.map { it.name })

        while (project.tracks.size < Project.MAX_TRACKS) {
            project = project.addTrack()
        }

        assertSame(project, project.addTrack())
    }

    @Test
    fun aProjectKeepsItsLastTrack() {
        val single = Project()
        assertSame(single, single.removeTrack(0))

        val two = single.addTrack("Bass")
        assertEquals(listOf("Bass"), two.removeTrack(0).tracks.map { it.name })
        assertSame(two, two.removeTrack(7))
    }

    @Test
    fun clipsGetTheNextIdInTheirTrack() {
        val project = Project().addTrack().addClip(0, 2).addClip(0, 1, "Fill").addClip(1, 4)

        assertEquals(listOf(1, 2), project.tracks[0].clips.map { it.id })
        assertEquals(listOf("Clip 1", "Fill"), project.tracks[0].clips.map { it.name })
        assertEquals(listOf(1), project.tracks[1].clips.map { it.id })
        assertEquals(4, project.tracks[1].clips[0].lengthBars)
    }

    @Test
    fun placementsCannotOverlap() {
        val project = projectWithClip(2)
        val first = project.placeClip(0, 1, 0)

        assertNotNull(first)
        // One bar of overlap
        assertNull(first!!.placeClip(0, 1, 1))

        // Right after the first one
        val second = first.placeClip(0, 1, 2)

        assertNotNull(second)
        assertEquals(listOf(0, 2), second!!.tracks[0].placements.map { it.startBar })
        assertEquals(listOf(1, 2), second.tracks[0].placements.map { it.id })
        assertEquals(4, second.tracks[0].endBar)

        // Unknown clip, unknown track, negative bar, and past the longest song
        assertNull(project.placeClip(0, 9, 0))
        assertNull(project.placeClip(3, 1, 0))
        assertNull(project.placeClip(0, 1, -1))
        assertNull(project.placeClip(0, 1, Project.MAX_LENGTH_BARS - 1))
    }

    @Test
    fun placingKeepsBarOrder() {
        val project = projectWithClip(1).placeClip(0, 1, 5)!!.placeClip(0, 1, 1)!!.placeClip(0, 1, 3)!!

        assertEquals(listOf(1, 3, 5), project.tracks[0].placements.map { it.startBar })
    }

    @Test
    fun aPlacementCanMoveOverItsOwnSpaceButNotOverAnother() {
        // Two bars at bar 0 (id 1) and at bar 4 (id 2)
        val project = projectWithClip(2).placeClip(0, 1, 0)!!.placeClip(0, 1, 4)!!

        // Half a clip to the right: over where it was, and short of the other one
        val moved = project.movePlacement(0, 1, 1)

        assertNotNull(moved)
        assertEquals(listOf(1, 4), moved!!.tracks[0].placements.map { it.startBar })
        // Ending at bar 5, into the other placement
        assertNull(project.movePlacement(0, 1, 3))
        assertNull(project.movePlacement(0, 99, 8))
    }

    @Test
    fun duplicatingAPlacementPlacesTheClipRightAfterIt() {
        val project = projectWithClip(2).placeClip(0, 1, 0)!!

        val twice = project.duplicatePlacement(0, 1)

        assertNotNull(twice)
        assertEquals(listOf(0, 2), twice!!.tracks[0].placements.map { it.startBar })
        assertEquals(listOf(1, 1), twice.tracks[0].placements.map { it.clipId })

        // Another placement is in the way, from bar 2
        assertNull(twice.duplicatePlacement(0, 1))
    }

    @Test
    fun removingAClipRemovesItsPlacements() {
        // Placement 1: Riff at bar 0, 2: Other at bar 1, 3: Riff at bar 2
        val project = projectWithClip(1).addClip(0, 1, "Other")
            .placeClip(0, 1, 0)!!.placeClip(0, 2, 1)!!.placeClip(0, 1, 2)!!

        val withoutRiff = project.removeClip(0, 1)

        assertEquals(listOf(2), withoutRiff.tracks[0].clips.map { it.id })
        assertEquals(listOf(2), withoutRiff.tracks[0].placements.map { it.id })
        assertEquals(listOf(2, 3), project.removePlacement(0, 1).tracks[0].placements.map { it.id })
        // The clip stays: only the placement went
        assertEquals(2, project.removePlacement(0, 1).tracks[0].clips.size)
    }

    @Test
    fun aClipEditedOnceChangesEverywhereItIsUsed() {
        val project = projectWithClip(1).placeClip(0, 1, 0)!!.placeClip(0, 1, 1)!!

        val edited = project.editNotes(0, 1) { notes, lengthTicks ->
            notes + ClipNote(notes.size + 1, 60, 0, lengthTicks / 2)
        }

        val clip = edited.tracks[0].clip(1)!!
        assertEquals(1, clip.notes.size)
        assertEquals(MidiSequencer.TICKS_PER_BAR / 2, clip.notes[0].durationTicks)
        // Both placements point at the one clip
        assertEquals(listOf(1, 1), edited.tracks[0].placements.map { it.clipId })
    }

    @Test
    fun duplicatingAClipCopiesItsNotesAndPlacesNothing() {
        val project = projectWithClip(1).editNotes(0, 1) { notes, _ -> notes + ClipNote(1, 60, 0, 100) }

        val copied = project.duplicateClip(0, 1)

        assertEquals(listOf(1, 2), copied.tracks[0].clips.map { it.id })
        assertEquals("Riff copy", copied.tracks[0].clips[1].name)
        assertEquals(project.tracks[0].clips[0].notes, copied.tracks[0].clips[1].notes)
        assertTrue(copied.tracks[0].placements.isEmpty())
    }

    @Test
    fun resizingAClipIsRefusedWhenItWouldOverlapThePlacementAfterIt() {
        // One bar at bar 0 and at bar 2
        val project = projectWithClip(1).placeClip(0, 1, 0)!!.placeClip(0, 1, 2)!!

        // Two bars: the first placement now ends where the second one starts
        val longer = project.setClipLength(0, 1, 2)
        assertEquals(2, longer.tracks[0].clip(1)!!.lengthBars)

        // Three bars: the first placement would run into the second
        assertSame(longer, longer.setClipLength(0, 1, 3))
    }

    @Test
    fun theSongIsAsLongAsItsLastPlacementOrItsMinimum() {
        val project = projectWithClip(2).setMinLengthBars(4)
        assertEquals(4, project.lengthBars)

        val longer = project.placeClip(0, 1, 6)!!
        assertEquals(8, longer.lengthBars)
        // The minimum never cuts the song below its content
        assertEquals(8, longer.setMinLengthBars(1).lengthBars)

        assertEquals(1, Project().setMinLengthBars(0).lengthBars)
        assertEquals(Project.MAX_LENGTH_BARS, Project().setMinLengthBars(5000).lengthBars)
    }

    @Test
    fun sanitizingDropsWhatCannotPlay() {
        val clip = Clip(
            1, "A", 1,
            listOf(
                ClipNote(1, 60, 0, 500),
                // Past the clip's end, then out of the pitch range, then a repeated id with a bad velocity
                ClipNote(2, 60, 99999, 500),
                ClipNote(3, 200, 0, 500),
                ClipNote(1, 62, 100, 100000, Float.NaN)
            )
        )
        val track = Track(
            "T",
            listOf(clip, clip.copy(name = "same id")),
            listOf(
                Placement(1, 1, 0),
                // Overlaps the first one, then names a clip the track lacks, then repeats an id
                Placement(2, 1, 0),
                Placement(3, 9, 5),
                Placement(1, 1, 3)
            ),
            levelDb = 500f
        )

        val project = Project(listOf(track), 0).sanitized()
        val clean = project.tracks[0]

        assertEquals(1, clean.clips.size)
        assertEquals(listOf(1), clean.placements.map { it.id })
        assertEquals(12f, clean.levelDb, 0f)
        assertEquals(1, project.minLengthBars)

        val notes = clean.clips[0].notes
        assertEquals(2, notes.size)
        assertEquals(2, notes.map { it.id }.toSet().size)
        // The long note is cut at the clip's end, and a velocity that is not a number becomes the default
        assertEquals(MidiSequencer.TICKS_PER_BAR - 100, notes[1].durationTicks)
        assertEquals(ClipNote.DEFAULT_VELOCITY, notes[1].velocity, 0f)
    }

    @Test
    fun sanitizingKeepsAtLeastOneTrackAndAtMostTheRacksTracks() {
        assertEquals(1, Project(emptyList(), 4).sanitized().tracks.size)
        assertEquals(Project.MAX_TRACKS, Project(List(20) { Track("T$it") }, 4).sanitized().tracks.size)
    }

    @Test
    fun placingOverReplacesWhatItCovers() {
        // Riff (2 bars, id 1) at bars 0-2 and 2-4, and Short (1 bar, id 2) at bar 6
        val project = projectWithClip(2).addClip(0, 1, "Short")
            .placeClip(0, 1, 0)!!.placeClip(0, 1, 2)!!.placeClip(0, 2, 6)!!

        // Short at bar 1 covers bars 1-2: the first Riff is in the way, the second one starts after it
        val replaced = project.placeClipReplacing(0, 2, 1)

        assertNotNull(replaced)
        assertEquals(listOf(1, 2, 6), replaced!!.tracks[0].placements.map { it.startBar })
        assertEquals(listOf(2, 1, 2), replaced.tracks[0].placements.map { it.clipId })
        // The clips are all still there
        assertEquals(2, replaced.tracks[0].clips.size)

        assertNull(project.placeClipReplacing(0, 2, Project.MAX_LENGTH_BARS))
        assertNull(project.placeClipReplacing(0, 9, 1))
        assertNull(project.placeClipReplacing(4, 2, 1))
    }

    @Test
    fun aRecordedClipIsAddedAndPlaced() {
        val notes = listOf(ClipNote(1, 60, 0, 100))

        val project = Project().addRecordedClip(0, 2, 3, notes, "Take")

        assertNotNull(project)
        val track = project!!.tracks[0]
        assertEquals(listOf("Take"), track.clips.map { it.name })
        assertEquals(3, track.clips[0].lengthBars)
        assertEquals(notes, track.clips[0].notes)
        assertEquals(listOf(2), track.placements.map { it.startBar })

        // Where it does not fit nothing is added
        assertNull(Project().addRecordedClip(0, Project.MAX_LENGTH_BARS - 1, 3, notes, "Late"))
    }

    @Test
    fun clearingTheSongKeepsTheTracksAndTheirMix() {
        val project = projectWithClip(1).placeClip(0, 1, 0)!!.addTrack("Bass").updateTrack(0) { it.copy(levelDb = -3f) }

        val cleared = project.clearSong()

        assertEquals(listOf("Track 1", "Bass"), cleared.tracks.map { it.name })
        assertTrue(cleared.tracks.all { it.clips.isEmpty() && it.placements.isEmpty() })
        assertEquals(-3f, cleared.tracks[0].levelDb, 0f)
        assertEquals(false, cleared.hasPlacements)
    }
}
