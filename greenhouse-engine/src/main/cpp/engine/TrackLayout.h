#pragma once

#include <cstdint>

namespace greenhouse
{

/**
 * How the rack's slots are laid out in tracks. A track is a serial chain: its instrument in the first
 * slot, then its effect slots, which process the instrument's output in place. The tracks run side by
 * side and are summed into the master. Slots are numbered across the rack, track by track:
 * slot = track * SLOTS_PER_TRACK + position in the track.
 */
constexpr int32_t EFFECT_SLOTS_PER_TRACK = 3;
constexpr int32_t INSTRUMENT_SLOT_IN_TRACK = 0;
constexpr int32_t SLOTS_PER_TRACK = INSTRUMENT_SLOT_IN_TRACK + 1 + EFFECT_SLOTS_PER_TRACK;
constexpr int32_t MAX_TRACKS = 8;
constexpr int32_t MAX_RACK_SLOTS = MAX_TRACKS * SLOTS_PER_TRACK;

constexpr int32_t getTrackOfSlot(int32_t slotIndex)
{
    return slotIndex / SLOTS_PER_TRACK;
}

constexpr int32_t getInstrumentSlotOfTrack(int32_t trackIndex)
{
    return trackIndex * SLOTS_PER_TRACK + INSTRUMENT_SLOT_IN_TRACK;
}

constexpr bool isInstrumentSlot(int32_t slotIndex)
{
    return slotIndex % SLOTS_PER_TRACK == INSTRUMENT_SLOT_IN_TRACK;
}

} // namespace greenhouse
