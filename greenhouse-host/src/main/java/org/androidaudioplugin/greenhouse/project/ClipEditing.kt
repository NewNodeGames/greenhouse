package org.androidaudioplugin.greenhouse.project

/**
 * Edits to the notes of a clip, as the piano roll makes them. Pure functions over the clip's notes,
 * each returning the changed list; [lengthTicks] is the clip's length, which no note goes past.
 * Notes are addressed by their [ClipNote.id].
 */
object ClipEditing {
    /** Adds a note, kept inside the clip, with the next free id. */
    fun addNote(
        notes: List<ClipNote>,
        note: Int,
        startTick: Int,
        durationTicks: Int,
        velocity: Float,
        lengthTicks: Int
    ): List<ClipNote> {
        val start = startTick.coerceIn(0, lengthTicks - ClipNote.MIN_DURATION_TICKS)
        val duration = durationTicks.coerceIn(ClipNote.MIN_DURATION_TICKS, lengthTicks - start)
        val id = (notes.maxOfOrNull { it.id } ?: 0) + 1

        return notes + ClipNote(
            id = id,
            note = note.coerceIn(ClipNote.MIN_NOTE, ClipNote.MAX_NOTE),
            startTick = start,
            durationTicks = duration,
            velocity = velocity.coerceIn(0f, 1f)
        )
    }

    /**
     * Moves the notes of [ids] by [deltaTicks] in time and [deltaPitch] semitones. They move as a
     * group: the move stops when one of them reaches the clip's edges or the end of the pitch range.
     */
    fun move(notes: List<ClipNote>, ids: Set<Int>, deltaTicks: Int, deltaPitch: Int, lengthTicks: Int): List<ClipNote> {
        val selected = notes.filter { it.id in ids }

        if (selected.isEmpty()) {
            return notes
        }

        val earliestDelta = -selected.minOf { it.startTick }
        val latestDelta = maxOf(earliestDelta, lengthTicks - selected.maxOf { it.endTick })
        val lowestDelta = ClipNote.MIN_NOTE - selected.minOf { it.note }
        val highestDelta = maxOf(lowestDelta, ClipNote.MAX_NOTE - selected.maxOf { it.note })
        val tickShift = deltaTicks.coerceIn(earliestDelta, latestDelta)
        val pitchShift = deltaPitch.coerceIn(lowestDelta, highestDelta)

        return notes.map { note ->
            if (note.id in ids) {
                note.copy(note = note.note + pitchShift, startTick = note.startTick + tickShift)
            } else {
                note
            }
        }
    }

    /** Sets the length of one note, keeping its start; it stops at the clip's end and lasts at least a tick. */
    fun resize(notes: List<ClipNote>, id: Int, durationTicks: Int, lengthTicks: Int): List<ClipNote> {
        return notes.map { note ->
            if (note.id == id) {
                val longest = maxOf(ClipNote.MIN_DURATION_TICKS, lengthTicks - note.startTick)
                note.copy(durationTicks = durationTicks.coerceIn(ClipNote.MIN_DURATION_TICKS, longest))
            } else {
                note
            }
        }
    }

    fun delete(notes: List<ClipNote>, ids: Set<Int>): List<ClipNote> {
        return notes.filter { it.id !in ids }
    }

    fun setVelocity(notes: List<ClipNote>, ids: Set<Int>, velocity: Float): List<ClipNote> {
        return notes.map { note ->
            if (note.id in ids) {
                note.copy(velocity = velocity.coerceIn(0f, 1f))
            } else {
                note
            }
        }
    }

    /**
     * Snaps the start of the notes of [ids] (all of them when null) to the nearest line of a grid of
     * [gridTicks]. A note keeps its length, shortened if it would end past the clip.
     */
    fun quantize(notes: List<ClipNote>, ids: Set<Int>?, gridTicks: Int, lengthTicks: Int): List<ClipNote> {
        if (gridTicks <= 0) {
            return notes
        }

        return notes.map { note ->
            if (ids == null || note.id in ids) {
                val snapped = ((note.startTick + gridTicks / 2) / gridTicks) * gridTicks
                val start = snapped.coerceIn(0, lengthTicks - ClipNote.MIN_DURATION_TICKS)
                note.copy(startTick = start, durationTicks = minOf(note.durationTicks, lengthTicks - start))
            } else {
                note
            }
        }
    }

    /**
     * Adds a copy of the notes of [ids], [shiftTicks] later. Each copy keeps its pitch, length and velocity and
     * gets a new id: the next free ones, in the order of the notes in time. A copy that would start past the end
     * of the clip is left out, and one that would end past it is cut.
     */
    fun duplicate(notes: List<ClipNote>, ids: Set<Int>, shiftTicks: Int, lengthTicks: Int): List<ClipNote> {
        var nextId = (notes.maxOfOrNull { it.id } ?: 0) + 1
        val copies = ArrayList<ClipNote>()

        for (original in notes.filter { it.id in ids }.sortedBy { it.startTick }) {
            val start = original.startTick + shiftTicks

            if (start < 0 || start >= lengthTicks) {
                continue
            }

            copies.add(original.copy(id = nextId, startTick = start, durationTicks = minOf(original.durationTicks, lengthTicks - start)))
            nextId++
        }

        return notes + copies
    }

    /**
     * Lengthens the notes of [ids] by [deltaTicks] (shortens them if it is negative). A note lasts at least a
     * tick and stops at the end of the clip.
     */
    fun adjustDuration(notes: List<ClipNote>, ids: Set<Int>, deltaTicks: Int, lengthTicks: Int): List<ClipNote> {
        return notes.map { note ->
            if (note.id in ids) {
                val longest = maxOf(ClipNote.MIN_DURATION_TICKS, lengthTicks - note.startTick)
                note.copy(durationTicks = (note.durationTicks + deltaTicks).coerceIn(ClipNote.MIN_DURATION_TICKS, longest))
            } else {
                note
            }
        }
    }

    /** Raises the velocity of the notes of [ids] by [delta] (lowers it if negative), within 0 to 1. */
    fun changeVelocity(notes: List<ClipNote>, ids: Set<Int>, delta: Float): List<ClipNote> {
        return notes.map { note ->
            if (note.id in ids) {
                note.copy(velocity = (note.velocity + delta).coerceIn(0f, 1f))
            } else {
                note
            }
        }
    }

    /**
     * The ids of the notes that touch a rectangle of the editor: time from [startTick] to [endTick],
     * pitch from [lowestNote] to [highestNote]. A note touches it when it overlaps in time.
     */
    fun notesIn(notes: List<ClipNote>, startTick: Int, endTick: Int, lowestNote: Int, highestNote: Int): Set<Int> {
        return notes
            .filter { it.note in lowestNote..highestNote && it.startTick < endTick && it.endTick > startTick }
            .map { it.id }
            .toSet()
    }

    /** The clip with a new length: notes starting after the new end are dropped and the others cut at it. */
    fun fitToLength(clip: Clip, lengthBars: Int): Clip {
        val bars = lengthBars.coerceIn(Clip.MIN_LENGTH_BARS, Project.MAX_LENGTH_BARS)
        val resized = clip.copy(lengthBars = bars)
        val lengthTicks = resized.lengthTicks
        val fitted = clip.notes
            .filter { it.startTick < lengthTicks }
            .map { it.copy(durationTicks = minOf(it.durationTicks, lengthTicks - it.startTick)) }

        return resized.copy(notes = fitted)
    }
}
