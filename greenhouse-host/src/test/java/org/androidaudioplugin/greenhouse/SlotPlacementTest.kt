package org.androidaudioplugin.greenhouse

import org.androidaudioplugin.greenhouse.data.SlotPlacement
import org.androidaudioplugin.greenhouse.data.SlotState
import org.androidaudioplugin.greenhouse.data.SlotType
import org.junit.Assert.assertEquals
import org.junit.Test

class SlotPlacementTest {

    companion object {
        private const val MIDI_FX = "MIDI FX"
        private val CURRENT_RACK = listOf(SlotType.INSTRUMENT, SlotType.EFFECT, SlotType.EFFECT)
        // A rack of two tracks, each an instrument and three effects
        private const val SLOTS_PER_TRACK = 4
        private val TWO_TRACKS = List(2) { listOf(SlotType.INSTRUMENT, SlotType.EFFECT, SlotType.EFFECT, SlotType.EFFECT) }.flatten()
    }

    private fun slot(index: Int, type: String, pluginId: String? = null): SlotState {
        return SlotState(slotIndex = index, slotType = type, pluginId = pluginId)
    }

    private fun session(): List<SlotState> {
        return listOf(
            slot(0, SlotType.INSTRUMENT, "synth"),
            slot(1, SlotType.EFFECT),
            slot(2, SlotType.EFFECT, "delay")
        )
    }

    @Test
    fun sameLayoutKeepsTheIndices() {
        val placed = SlotPlacement.place(session(), CURRENT_RACK)

        assertEquals(listOf(0, 1, 2), placed.map { it.slotIndex })
        assertEquals(listOf("synth", null, "delay"), placed.map { it.pluginId })
    }

    @Test
    fun slotsFollowTheirRoleWhenTheLayoutChanges() {
        // A rack with a new slot type in front of the instrument
        val rack = listOf(MIDI_FX, SlotType.INSTRUMENT, SlotType.EFFECT, SlotType.EFFECT)

        val placed = SlotPlacement.place(session(), rack)

        assertEquals(listOf(1, 2, 3), placed.map { it.slotIndex })
        // Effects keep their order, including the empty one in front
        assertEquals(listOf("synth", null, "delay"), placed.map { it.pluginId })
    }

    @Test
    fun unknownTypesAndSlotsWithoutRoomAreLeftOut() {
        val saved = listOf(slot(0, MIDI_FX, "arp")) + session().map { it.copy(slotIndex = it.slotIndex + 1) }
        val rack = listOf(SlotType.INSTRUMENT, SlotType.EFFECT)

        val placed = SlotPlacement.place(saved, rack)

        assertEquals(listOf(0, 1), placed.map { it.slotIndex })
        assertEquals(listOf("synth", null), placed.map { it.pluginId })
    }

    @Test
    fun savedOrderIsTheSlotIndexOrder() {
        val placed = SlotPlacement.place(session().reversed(), CURRENT_RACK)

        assertEquals(listOf("synth", null, "delay"), placed.map { it.pluginId })
    }

    /** Two tracks saved with five slots each: an instrument and four effects, the first and last of each loaded. */
    private fun twoFiveSlotTracks(): List<SlotState> {
        return listOf(
            slot(0, SlotType.INSTRUMENT, "synthA"), slot(1, SlotType.EFFECT, "fxA1"), slot(2, SlotType.EFFECT),
            slot(3, SlotType.EFFECT), slot(4, SlotType.EFFECT, "fxA4"),
            slot(5, SlotType.INSTRUMENT, "synthB"), slot(6, SlotType.EFFECT, "fxB1"), slot(7, SlotType.EFFECT),
            slot(8, SlotType.EFFECT), slot(9, SlotType.EFFECT, "fxB4")
        )
    }

    @Test
    fun slotsStayInTheirTrackWhenTracksHaveFewerEffects() {
        val placed = SlotPlacement.placeInTracks(twoFiveSlotTracks(), 5, TWO_TRACKS, SLOTS_PER_TRACK)

        // The fourth effect of each track has no room, and the second track starts at its own slot 4
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7), placed.map { it.slotIndex })
        assertEquals(listOf("synthA", "fxA1", null, null, "synthB", "fxB1", null, null), placed.map { it.pluginId })
    }

    @Test
    fun slotsGoBackToTheirPlaceWhenTracksGetMoreEffects() {
        // Saved with four slots a track, loaded in a rack with five
        val rack = List(2) { listOf(SlotType.INSTRUMENT, SlotType.EFFECT, SlotType.EFFECT, SlotType.EFFECT, SlotType.EFFECT) }.flatten()
        val saved = listOf(
            slot(0, SlotType.INSTRUMENT, "synthA"), slot(3, SlotType.EFFECT, "fxA3"),
            slot(4, SlotType.INSTRUMENT, "synthB"), slot(5, SlotType.EFFECT, "fxB1")
        )

        val placed = SlotPlacement.placeInTracks(saved, 4, rack, 5)

        assertEquals(listOf(0, 3, 5, 6), placed.map { it.slotIndex })
        assertEquals(listOf("synthA", "fxA3", "synthB", "fxB1"), placed.map { it.pluginId })
    }

    @Test
    fun aTrackTheRackLacksIsLeftOut() {
        val saved = List(12) { slot(it, if (it % SLOTS_PER_TRACK == 0) SlotType.INSTRUMENT else SlotType.EFFECT) }

        val placed = SlotPlacement.placeInTracks(saved, SLOTS_PER_TRACK, TWO_TRACKS, SLOTS_PER_TRACK)

        assertEquals((0 until 8).toList(), placed.map { it.slotIndex })
    }

    @Test
    fun aSmallSessionWithoutTheLayoutIsASingleTrackPlacedByRole() {
        val placed = SlotPlacement.placeInTracks(session(), null, TWO_TRACKS, SLOTS_PER_TRACK)

        assertEquals(listOf(0, 1, 2), placed.map { it.slotIndex })
        assertEquals(listOf("synth", null, "delay"), placed.map { it.pluginId })
    }

    @Test
    fun aFiveSlotSessionWithoutTheLayoutKeepsItsEffectsInTheFirstTrack() {
        // One track saved with four effects: the fourth must not end up in the second track
        val saved = twoFiveSlotTracks().take(5)

        val placed = SlotPlacement.placeInTracks(saved, null, TWO_TRACKS, SLOTS_PER_TRACK)

        assertEquals(listOf(0, 1, 2, 3), placed.map { it.slotIndex })
        assertEquals(listOf("synthA", "fxA1", null, null), placed.map { it.pluginId })
    }

    @Test
    fun aLargeSessionWithoutTheLayoutHadFiveSlotsPerTrack() {
        val placed = SlotPlacement.placeInTracks(twoFiveSlotTracks(), null, TWO_TRACKS, SLOTS_PER_TRACK)

        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7), placed.map { it.slotIndex })
        assertEquals(listOf("synthA", "fxA1", null, null, "synthB", "fxB1", null, null), placed.map { it.pluginId })
    }
}
