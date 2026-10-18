package org.androidaudioplugin.greenhouse.project

import kotlin.math.roundToInt

/**
 * The notes of a clip as the ints saved in a session (Base64 through `SequenceState.encodeEvents()`):
 * pitch, start tick, duration in ticks and velocity in thousandths, for each note. Ids are not saved:
 * they are numbered again, in order, when the notes are read.
 */
object ClipNoteCodec {
    private const val NOTE = 0
    private const val START_TICK = 1
    private const val DURATION_TICKS = 2
    private const val VELOCITY_PERMILLE = 3
    private const val STRIDE = 4
    private const val PERMILLE = 1000f

    fun pack(notes: List<ClipNote>): IntArray {
        val packed = IntArray(notes.size * STRIDE)

        for ((index, note) in notes.withIndex()) {
            val base = index * STRIDE

            packed[base + NOTE] = note.note
            packed[base + START_TICK] = note.startTick
            packed[base + DURATION_TICKS] = note.durationTicks
            packed[base + VELOCITY_PERMILLE] = (note.velocity * PERMILLE).roundToInt()
        }

        return packed
    }

    /** A trailing partial note is ignored. The notes are not validated: see [sanitized]. */
    fun unpack(packed: IntArray): List<ClipNote> {
        val notes = ArrayList<ClipNote>()
        var base = 0

        while (base + STRIDE <= packed.size) {
            notes.add(
                ClipNote(
                    id = notes.size + 1,
                    note = packed[base + NOTE],
                    startTick = packed[base + START_TICK],
                    durationTicks = packed[base + DURATION_TICKS],
                    velocity = packed[base + VELOCITY_PERMILLE] / PERMILLE
                )
            )
            base += STRIDE
        }

        return notes
    }
}
