package org.androidaudioplugin.greenhouse.project

import org.androidaudioplugin.greenhouse.core.MidiSequencer
import org.androidaudioplugin.greenhouse.data.SlotHostSettings

/** A note of a [Clip]. Ticks count from the start of the clip. */
data class ClipNote(
    /** Unique within its clip: what the editor selects and edits notes by. */
    val id: Int,
    val note: Int,
    val startTick: Int,
    val durationTicks: Int,
    /** From 0 to 1. */
    val velocity: Float = DEFAULT_VELOCITY,
    /**
     * The chance the note is played each time the song reaches it, in percent: one of [PROBABILITY_LEVELS]. The
     * engine rolls for it as it plays, from the project's seed (see [Project.probabilitySeed]).
     */
    val probability: Int = DEFAULT_PROBABILITY
) {
    val endTick: Int
        get() = startTick + durationTicks

    companion object {
        const val MIN_NOTE = 0
        const val MAX_NOTE = 127
        const val DEFAULT_PROBABILITY = 100
        val PROBABILITY_LEVELS = listOf(0, 25, 50, 75, 100)

        /** The level nearest to [percent] (the lower one when halfway). */
        fun snapProbability(percent: Int): Int {
            return (percent.coerceIn(0, DEFAULT_PROBABILITY) + 12) / 25 * 25
        }

        /** A note shorter than this would end before it starts. */
        const val MIN_DURATION_TICKS = 1
        const val DEFAULT_VELOCITY = 0.8f
    }
}

/**
 * A sequence of notes of a [Track], a whole number of bars long. Placed in the arrangement as many
 * times as wanted ([Placement]): editing the clip changes every place it is used.
 */
data class Clip(
    /** Unique within its track. */
    val id: Int,
    val name: String,
    val lengthBars: Int,
    val notes: List<ClipNote> = emptyList()
) {
    val lengthTicks: Int
        get() = lengthBars * MidiSequencer.TICKS_PER_BAR

    /** The id the next note added to the clip gets. */
    val nextNoteId: Int
        get() = (notes.maxOfOrNull { it.id } ?: 0) + 1

    companion object {
        const val MIN_LENGTH_BARS = 1
    }
}

/** A [Clip] of a track placed in the arrangement from [startBar] (from 0), for the length of the clip. */
data class Placement(
    /** Unique within its track. */
    val id: Int,
    val clipId: Int,
    val startBar: Int
)

/**
 * A track of the arrangement: the clips that play on one instrument of the rack and where they are
 * placed. Track n of the project is track n of the rack (see `RackEngine`), with its own mix settings.
 */
data class Track(
    val name: String,
    val clips: List<Clip> = emptyList(),
    /** In bar order, never overlapping. */
    val placements: List<Placement> = emptyList(),
    /** Level of the track in the master mix. */
    val levelDb: Float = SlotHostSettings.DEFAULT_LEVEL_DB,
    /** Where the track sits in the stereo mix: -1 all the way left, 0 the centre, 1 all the way right. */
    val pan: Float = CENTER_PAN,
    val isMuted: Boolean = false,
    val isSoloed: Boolean = false
) {
    /** The id the next clip added to the track gets. */
    val nextClipId: Int
        get() = (clips.maxOfOrNull { it.id } ?: 0) + 1

    /** The id the next placement added to the track gets. */
    val nextPlacementId: Int
        get() = (placements.maxOfOrNull { it.id } ?: 0) + 1

    /** The bar after the last placement; 0 for a track without any. */
    val endBar: Int
        get() = placements.maxOfOrNull { endBarOf(it) } ?: 0

    fun clip(clipId: Int): Clip? {
        return clips.firstOrNull { it.id == clipId }
    }

    companion object {
        const val MIN_PAN = -1f
        const val CENTER_PAN = 0f
        const val MAX_PAN = 1f
    }

    /** The bar a placement ends at (exclusive). A placement of a clip the track lacks takes no room. */
    fun endBarOf(placement: Placement): Int {
        return placement.startBar + (clip(placement.clipId)?.lengthBars ?: 0)
    }

    /**
     * Whether [clipId] can be placed from [startBar]: the clip exists, the placement stays within the
     * song and no other placement overlaps it. [ignoredPlacementId]: a placement that is being moved.
     */
    fun canPlace(clipId: Int, startBar: Int, ignoredPlacementId: Int? = null): Boolean {
        val clip = clip(clipId) ?: return false
        val clipEndBar = startBar + clip.lengthBars

        if (startBar < 0 || clipEndBar > Project.MAX_LENGTH_BARS) {
            return false
        }

        return placements.none { other ->
            other.id != ignoredPlacementId && startBar < endBarOf(other) && other.startBar < clipEndBar
        }
    }
}

/**
 * What a session plays: [tracks] in rack order, each with its clips arranged along the bars of the
 * song. Immutable: the editing functions (ProjectEditing.kt) return the changed project.
 */
data class Project(
    val tracks: List<Track> = listOf(Track(DEFAULT_TRACK_NAME)),
    /** The song is at least this long even when the clips end sooner. */
    val minLengthBars: Int = DEFAULT_LENGTH_BARS,
    /** Seeds the chance of notes: the same seed plays the same song, another seed another variation. */
    val probabilitySeed: Int = DEFAULT_PROBABILITY_SEED
) {
    /** Whether anything is arranged: an empty song has no length of its own yet. */
    val hasPlacements: Boolean
        get() = tracks.any { it.placements.isNotEmpty() }

    /** Bars of the song: [minLengthBars], or up to the last placement if that is later. */
    val lengthBars: Int
        get() = maxOf(minLengthBars, tracks.maxOfOrNull { it.endBar } ?: 0).coerceIn(MIN_LENGTH_BARS, MAX_LENGTH_BARS)

    companion object {
        const val DEFAULT_TRACK_NAME = "Track 1"
        const val MIN_LENGTH_BARS = 1
        /** What the engine's sequencer can loop. */
        const val MAX_LENGTH_BARS = 999
        const val DEFAULT_LENGTH_BARS = 4
        const val DEFAULT_PROBABILITY_SEED = 1
        /** Seeds are small numbers, to read and to type. */
        const val MAX_PROBABILITY_SEED = 9999
        const val MAX_TRACKS = MidiSequencer.MAX_TRACKS
    }
}

/**
 * The project with everything that could be inconsistent put right: for data read from a file,
 * which may come from anywhere. Unusable notes and placements are dropped, and values are clamped.
 */
fun Project.sanitized(): Project {
    val cleanTracks = tracks.take(Project.MAX_TRACKS).map { it.sanitized() }

    return Project(
        tracks = cleanTracks.ifEmpty { listOf(Track(Project.DEFAULT_TRACK_NAME)) },
        minLengthBars = minLengthBars.coerceIn(Project.MIN_LENGTH_BARS, Project.MAX_LENGTH_BARS),
        probabilitySeed = probabilitySeed.coerceIn(0, Project.MAX_PROBABILITY_SEED)
    )
}

fun Track.sanitized(): Track {
    val seenClipIds = HashSet<Int>()
    val cleanClips = clips.filter { seenClipIds.add(it.id) }.map { it.sanitized() }
    val cleanLevel = if (levelDb.isNaN()) {
        SlotHostSettings.DEFAULT_LEVEL_DB
    } else {
        levelDb.coerceIn(SlotHostSettings.MIN_LEVEL_DB, SlotHostSettings.MAX_LEVEL_DB)
    }

    val cleanPan = if (pan.isNaN()) Track.CENTER_PAN else pan.coerceIn(Track.MIN_PAN, Track.MAX_PAN)

    var result = Track(name, cleanClips, emptyList(), cleanLevel, cleanPan, isMuted, isSoloed)
    val seenPlacementIds = HashSet<Int>()

    // In bar order, a placement that would overlap an earlier one is the one left out
    for (placement in placements.sortedBy { it.startBar }) {
        if (seenPlacementIds.contains(placement.id) || !result.canPlace(placement.clipId, placement.startBar)) {
            continue
        }

        seenPlacementIds.add(placement.id)
        result = result.copy(placements = result.placements + placement)
    }

    return result
}

fun Clip.sanitized(): Clip {
    val bars = lengthBars.coerceIn(Clip.MIN_LENGTH_BARS, Project.MAX_LENGTH_BARS)
    val clipTicks = bars * MidiSequencer.TICKS_PER_BAR
    val seenIds = HashSet<Int>()
    var nextId = nextNoteId
    val cleanNotes = ArrayList<ClipNote>()

    for (note in notes) {
        if (note.note < ClipNote.MIN_NOTE || note.note > ClipNote.MAX_NOTE) {
            continue
        }

        if (note.startTick < 0 || note.startTick >= clipTicks) {
            continue
        }

        var id = note.id

        if (!seenIds.add(id)) {
            id = nextId
            nextId++
            seenIds.add(id)
        }

        val velocity = if (note.velocity.isNaN()) {
            ClipNote.DEFAULT_VELOCITY
        } else {
            note.velocity.coerceIn(0f, 1f)
        }

        cleanNotes.add(
            note.copy(
                id = id,
                durationTicks = note.durationTicks.coerceIn(ClipNote.MIN_DURATION_TICKS, clipTicks - note.startTick),
                velocity = velocity,
                probability = ClipNote.snapProbability(note.probability)
            )
        )
    }

    return copy(lengthBars = bars, notes = cleanNotes)
}
