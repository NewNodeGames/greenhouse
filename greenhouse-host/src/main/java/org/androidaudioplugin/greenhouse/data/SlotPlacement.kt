package org.androidaudioplugin.greenhouse.data

/** Roles of the rack slots, saved as `slotType` in .ghrack sessions. */
object SlotType {
    const val INSTRUMENT = "Instrument"
    const val EFFECT = "Effect"
}

/**
 * Places the slots of a saved session in the rack by role rather than by index: the n-th saved slot
 * of a type goes to the n-th rack slot of that type. Sessions keep loading when the rack layout
 * changes, e.g. when a slot of a new type is added in front of the instrument.
 */
object SlotPlacement {
    /**
     * Returns the saved slots that fit in a rack whose slots have [rackSlotTypes], with their
     * slotIndex in that rack. Slots of a type the rack doesn't have (e.g. saved by a newer version),
     * or beyond the rack's slots of their type, are left out.
     */
    fun place(saved: List<SlotState>, rackSlotTypes: List<String>): List<SlotState> {
        val placed = mutableListOf<SlotState>()

        for ((type, savedOfType) in saved.sortedBy { it.slotIndex }.groupBy { it.slotType }) {
            val targets = rackSlotTypes.indices.filter { rackSlotTypes[it] == type }

            savedOfType.zip(targets) { state, target ->
                placed.add(state.copy(slotIndex = target))
            }
        }

        return placed.sortedBy { it.slotIndex }
    }

    // Sessions saved with several tracks before they said how many slots a track had: five, the instrument and
    // four effects. A session with no more slots than that holds a single track.
    private const val LEGACY_SLOTS_PER_TRACK = 5

    /**
     * Like [place], for a rack of tracks: [rackSlotsPerTrack] slots each, [rackSlotTypes] in all. A session
     * says how many slots each of its tracks had ([savedSlotsPerTrack]): a slot goes to the same position of
     * the same track, so tracks stay apart when the number of effects per track changes. A position the
     * rack's tracks lack, or a track the rack lacks, is left out.
     *
     * Sessions that do not say ([savedSlotsPerTrack] null) are a single track unless they have more slots
     * than the five a track then had: the slots of a single track follow their role, as in [place], within
     * the first track of the rack.
     */
    fun placeInTracks(
        saved: List<SlotState>,
        savedSlotsPerTrack: Int?,
        rackSlotTypes: List<String>,
        rackSlotsPerTrack: Int
    ): List<SlotState> {
        val perTrack = savedSlotsPerTrack ?: if (saved.size > LEGACY_SLOTS_PER_TRACK) LEGACY_SLOTS_PER_TRACK else null

        if (perTrack == null || perTrack <= 0) {
            return place(saved, rackSlotTypes.take(rackSlotsPerTrack))
        }

        val rackTracks = rackSlotTypes.size / rackSlotsPerTrack
        val placed = mutableListOf<SlotState>()

        for (state in saved.sortedBy { it.slotIndex }) {
            val track = state.slotIndex / perTrack
            val position = state.slotIndex % perTrack

            if (state.slotIndex < 0 || track >= rackTracks || position >= rackSlotsPerTrack) {
                continue
            }

            placed.add(state.copy(slotIndex = track * rackSlotsPerTrack + position))
        }

        return placed
    }
}
