package org.androidaudioplugin.greenhouse

import org.androidaudioplugin.greenhouse.core.MidiSequencer
import org.androidaudioplugin.greenhouse.core.SequencerSettings
import org.androidaudioplugin.greenhouse.data.RackPreset
import org.androidaudioplugin.greenhouse.data.RackStateSerializer
import org.androidaudioplugin.greenhouse.data.SequenceState
import org.androidaudioplugin.greenhouse.project.Clip
import org.androidaudioplugin.greenhouse.project.ClipNote
import org.androidaudioplugin.greenhouse.project.ClipNoteCodec
import org.androidaudioplugin.greenhouse.project.Placement
import org.androidaudioplugin.greenhouse.project.Project
import org.androidaudioplugin.greenhouse.project.Track
import org.androidaudioplugin.greenhouse.project.resolveProject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ProjectSerializationTest {

    companion object {
        private const val BAR = MidiSequencer.TICKS_PER_BAR
        private const val BEAT = MidiSequencer.PPQ
        private const val MIDI2_NOTE_ON = 0x40900000
        private const val MIDI2_NOTE_OFF = 0x40800000
        private const val NOTE_SHIFT = 8
        private const val FULL_VELOCITY16 = 0xFFFF0000.toInt()
        private const val VELOCITY_TOLERANCE = 0.0001f
    }

    private fun event(tick: Int, word0: Int, word1: Int = 0, slot: Int = 0): IntArray {
        return intArrayOf(tick, slot, 0, 2, word0, word1, 0, 0)
    }

    private fun on(note: Int): Int = MIDI2_NOTE_ON or (note shl NOTE_SHIFT)

    private fun off(note: Int): Int = MIDI2_NOTE_OFF or (note shl NOTE_SHIFT)

    private fun sequenceOf(packed: IntArray, settings: SequencerSettings = SequencerSettings(), autoLengthBars: Int = 0): SequenceState {
        return SequenceState(settings, SequenceState.encodeEvents(packed), autoLengthBars)
    }

    private fun sampleProject(): Project {
        val riff = Clip(
            1, "Riff", 2,
            listOf(ClipNote(1, 60, 0, BEAT, 1f), ClipNote(2, 64, BEAT, BEAT / 2, 0.5f))
        )
        val lead = Track(
            "Lead", listOf(riff),
            listOf(Placement(1, 1, 0), Placement(2, 1, 2)),
            levelDb = -6f, pan = -0.5f, isMuted = true
        )
        val bass = Track("Bass", emptyList(), emptyList(), pan = 0.25f, isSoloed = true)

        return Project(listOf(lead, bass), 8)
    }

    @Test
    fun aProjectSurvivesTheSessionFile() {
        val preset = RackPreset(name = "Song", project = sampleProject())

        val deserialized = RackStateSerializer.deserializeFromJson(RackStateSerializer.serializeToJson(preset))

        assertNotNull(deserialized)
        assertEquals(sampleProject(), deserialized!!.project)
        assertEquals(RackPreset.CURRENT_SCHEMA_VERSION, deserialized.version)
        assertEquals(2, deserialized.version)
    }

    @Test
    fun sessionsWithoutAProjectStillLoad() {
        val json = """
            {"format":"greenhouse_rack_preset","version":1,"name":"Old","slots":[],
             "sequence":{"bpm":100.0,"lengthBars":1,"eventsBase64":null}}
        """.trimIndent()

        val preset = RackStateSerializer.deserializeFromJson(json)

        assertNotNull(preset)
        assertEquals(1, preset!!.version)
        assertNull(preset.project)
        assertEquals(Project(), preset.resolveProject())
    }

    @Test
    fun aSavedProjectIsNotReplacedByTheSequence() {
        val packed = event(0, on(60), FULL_VELOCITY16) + event(BEAT, off(60))
        val preset = RackPreset(name = "Song", sequence = sequenceOf(packed), project = sampleProject())

        assertEquals(sampleProject(), preset.resolveProject())
    }

    @Test
    fun aDamagedProjectLoadsAsWhatCanBeSaved() {
        val json = """
            {"name":"Damaged","project":{"minLengthBars":-3,"tracks":[
              {"name":"T","clips":[{"id":1,"name":"C","lengthBars":1,"notesBase64":"!!!not base64"}],
               "placements":[{"id":1,"clipId":1,"startBar":-5},{"id":2,"clipId":1,"startBar":0},{"id":3,"clipId":1,"startBar":0}]}
            ]}}
        """.trimIndent()

        val preset = RackStateSerializer.deserializeFromJson(json)

        assertNotNull(preset)
        val project = preset!!.project!!
        assertEquals(1, project.minLengthBars)
        assertEquals(1, project.tracks.size)
        assertEquals(emptyList<ClipNote>(), project.tracks[0].clips[0].notes)
        // The one before the first bar and the one overlapping another are gone
        assertEquals(listOf(2), project.tracks[0].placements.map { it.id })
    }

    @Test
    fun aProjectWithoutTracksLoadsWithOne() {
        val preset = RackStateSerializer.deserializeFromJson("""{"name":"Empty","project":{"tracks":[]}}""")

        assertEquals(1, preset!!.project!!.tracks.size)
    }

    @Test
    fun noteCodecRoundtripsAndIgnoresAPartialNote() {
        val notes = listOf(ClipNote(1, 60, 0, BEAT, 1f), ClipNote(2, 127, BAR - 1, 1, 0.25f))

        assertEquals(notes, ClipNoteCodec.unpack(ClipNoteCodec.pack(notes)))
        assertEquals(emptyList<ClipNote>(), ClipNoteCodec.unpack(intArrayOf(60, 0, BEAT)))
        assertEquals(emptyList<ClipNote>(), ClipNoteCodec.unpack(IntArray(0)))
    }

    @Test
    fun aLegacySequenceBecomesAClipPerTrack() {
        val packed = event(0, on(60), FULL_VELOCITY16) + event(BEAT, off(60)) +
            event(2 * BEAT, on(48), FULL_VELOCITY16, slot = 1) + event(3 * BEAT, off(48), slot = 1)
        val sequence = sequenceOf(packed, SequencerSettings(lengthBars = 2))

        val project = RackPreset(name = "Old", sequence = sequence).resolveProject()

        assertEquals(2, project.tracks.size)
        assertEquals(2, project.minLengthBars)
        assertEquals(2, project.lengthBars)

        for (track in project.tracks) {
            assertEquals(1, track.clips.size)
            assertEquals(2, track.clips[0].lengthBars)
            assertEquals(listOf(0), track.placements.map { it.startBar })
        }

        val first = project.tracks[0].clips[0].notes
        assertEquals(1, first.size)
        assertEquals(listOf(60, 0, BEAT), listOf(first[0].note, first[0].startTick, first[0].durationTicks))
        assertEquals(1f, first[0].velocity, VELOCITY_TOLERANCE)

        val second = project.tracks[1].clips[0].notes
        assertEquals(listOf(48, 2 * BEAT, BEAT), listOf(second[0].note, second[0].startTick, second[0].durationTicks))
    }

    @Test
    fun theLegacyLoopLengthIsTheSettingThenTheFirstTakeThenTheContent() {
        val packed = event(0, on(60), FULL_VELOCITY16) + event(5000, off(60))

        // A fixed length wins; then what the first take set; then enough bars for the last event
        assertEquals(3, sequenceOf(packed, SequencerSettings(lengthBars = 3), 8).let { RackPreset("A", sequence = it).resolveProject().lengthBars })
        assertEquals(5, sequenceOf(packed, SequencerSettings(), 5).let { RackPreset("B", sequence = it).resolveProject().lengthBars })
        assertEquals(2, sequenceOf(packed).let { RackPreset("C", sequence = it).resolveProject().lengthBars })
    }

    @Test
    fun aLegacyNoteHeldAcrossTheLoopEndIsSplitInTwoNotes() {
        val packed = event(BAR - 240, on(60), FULL_VELOCITY16) + event(200, off(60))

        val project = RackPreset("Old", sequence = sequenceOf(packed, SequencerSettings(lengthBars = 1))).resolveProject()

        val notes = project.tracks[0].clips[0].notes
        assertEquals(setOf(Pair(BAR - 240, 240), Pair(0, 200)), notes.map { Pair(it.startTick, it.durationTicks) }.toSet())
    }

    @Test
    fun anEmptyOrMissingLegacySequenceGivesTheDefaultProject() {
        assertEquals(Project(), RackPreset("None").resolveProject())
        assertEquals(Project(), RackPreset("Empty", sequence = SequenceState()).resolveProject())
    }

    @Test
    fun aTrackSavedWithoutPanIsInTheCentre() {
        val json = """{"name":"Old","project":{"tracks":[{"name":"T","levelDb":-3.0}]}}"""

        val track = RackStateSerializer.deserializeFromJson(json)!!.project!!.tracks[0]

        assertEquals(0f, track.pan, 0f)
        assertEquals(-3f, track.levelDb, 0f)
    }

    @Test
    fun aPanOutOfRangeIsBroughtBack() {
        val json = """{"name":"Odd","project":{"tracks":[{"name":"A","pan":7.5},{"name":"B","pan":-2.0}]}}"""

        val tracks = RackStateSerializer.deserializeFromJson(json)!!.project!!.tracks

        assertEquals(listOf(1f, -1f), tracks.map { it.pan })
    }
}
