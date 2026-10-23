package org.androidaudioplugin.greenhouse.project

import org.androidaudioplugin.greenhouse.core.MidiSequencer
import kotlin.math.roundToInt

/**
 * Turns the arrangement into what the engine's sequencer plays: one flat list of MIDI events, over the
 * whole song, each on the track it belongs to. The engine knows nothing of clips: after every edit the
 * host flattens the project again and swaps the result in (`MidiSequencer.replaceEvents()`).
 */
object ArrangementFlattener {
    /** What the engine's sequencer holds; later events are dropped (MAX_SEQUENCE_EVENTS in Sequence.h). */
    const val MAX_ENGINE_EVENTS = 65536

    // Notes as the engine's MIDI 2.0 UMP words (group 0, channel 0): status and note in the first word,
    // the 16-bit velocity in the top half of the second
    private const val MIDI2_NOTE_ON = 0x40900000
    private const val MIDI2_NOTE_OFF = 0x40800000
    private const val NOTE_SHIFT = 8
    private const val VELOCITY_SHIFT = 16
    private const val MAX_VELOCITY16 = 0xFFFF
    private const val UMP_WORD_COUNT = 2
    // A note with a chance below 100 percent keeps it, set apart by a flag, in the third word of its note-on, which
    // a MIDI 2.0 note does not use (see getNoteProbability() in Sequence.h): the engine rolls for it as it plays
    private const val PROBABILITY_SET_FLAG = 0x80000000.toInt()
    // The word count comes after the tick, slot and take in a packed event
    private const val EVENT_WORD_COUNT = 3
    // Flattened events belong to no take: what is recorded over them is told apart by its take
    private const val NO_TAKE = 0

    /** [packedEvents] are laid out like `MidiSequencer.getEvents()`, in time order. */
    class Result(val packedEvents: IntArray, val lengthBars: Int, val eventCount: Int) {
        /** The engine would drop the end of the song. */
        val exceedsEngineLimit: Boolean
            get() = eventCount > MAX_ENGINE_EVENTS
    }

    private class Event(val tick: Int, val track: Int, val isNoteOn: Boolean, val note: Int, val velocity16: Int, val probability: Int)

    fun flatten(project: Project): Result {
        val events = ArrayList<Event>()

        for ((trackIndex, track) in project.tracks.withIndex()) {
            if (trackIndex >= Project.MAX_TRACKS) {
                break
            }

            for (placement in track.placements) {
                val clip = track.clip(placement.clipId) ?: continue
                val offset = placement.startBar * MidiSequencer.TICKS_PER_BAR

                for (note in playableNotes(clip)) {
                    val end = minOf(note.startTick + maxOf(ClipNote.MIN_DURATION_TICKS, note.durationTicks), clip.lengthTicks)
                    val velocity16 = (note.velocity * MAX_VELOCITY16).roundToInt().coerceIn(1, MAX_VELOCITY16)

                    events.add(Event(offset + note.startTick, trackIndex, true, note.note, velocity16, note.probability))
                    events.add(Event(offset + end, trackIndex, false, note.note, 0, ClipNote.DEFAULT_PROBABILITY))
                }
            }
        }

        // A note ends before another starts at the same tick, so a repeated note is struck again
        events.sortWith(compareBy<Event>({ it.tick }, { if (it.isNoteOn) 1 else 0 }, { it.track }, { it.note }))

        return Result(pack(events), project.lengthBars, events.size)
    }

    private fun pack(events: List<Event>): IntArray {
        val packed = IntArray(events.size * MidiSequencer.EVENT_STRIDE)

        for ((index, event) in events.withIndex()) {
            val base = index * MidiSequencer.EVENT_STRIDE
            val status = if (event.isNoteOn) MIDI2_NOTE_ON else MIDI2_NOTE_OFF

            packed[base + MidiSequencer.EVENT_TICK] = event.tick
            packed[base + MidiSequencer.EVENT_SLOT] = event.track
            packed[base + MidiSequencer.EVENT_TAKE] = NO_TAKE
            packed[base + EVENT_WORD_COUNT] = UMP_WORD_COUNT
            packed[base + MidiSequencer.EVENT_WORDS] = status or (event.note shl NOTE_SHIFT)
            packed[base + MidiSequencer.EVENT_WORDS + 1] = if (event.isNoteOn) event.velocity16 shl VELOCITY_SHIFT else 0
            packed[base + MidiSequencer.EVENT_WORDS + 2] = if (event.isNoteOn && event.probability < ClipNote.DEFAULT_PROBABILITY) {
                PROBABILITY_SET_FLAG or event.probability
            } else {
                0
            }
        }

        return packed
    }

    /**
     * The notes of a clip as they are played. The engine pairs the note-offs with the note-ons of the
     * same pitch, so a note is cut where the next one of its pitch starts, and a note repeated at the
     * same tick plays once.
     */
    private fun playableNotes(clip: Clip): List<ClipNote> {
        val playable = ArrayList<ClipNote>()
        val byPitch = clip.notes.filter { it.startTick >= 0 && it.startTick < clip.lengthTicks }.groupBy { it.note }

        for (pitchNotes in byPitch.values) {
            var current: ClipNote? = null

            for (next in pitchNotes.sortedWith(compareBy<ClipNote>({ it.startTick }, { it.id }))) {
                val held = current

                if (held == null) {
                    current = next
                    continue
                }

                if (next.startTick == held.startTick) {
                    continue
                }

                playable.add(held.copy(durationTicks = minOf(held.durationTicks, next.startTick - held.startTick)))
                current = next
            }

            current?.let { playable.add(it) }
        }

        return playable
    }
}
