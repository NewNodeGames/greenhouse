package org.androidaudioplugin.greenhouse.project

/*
 * Edits to a [Project], each returning the changed project (the receiver itself when there is nothing
 * to change). Tracks are addressed by their index, clips and placements by their id within the track.
 * Operations that can be refused (a placement that would overlap another) return null instead.
 */

/** Applies [transform] to the track at [trackIndex]. */
fun Project.updateTrack(trackIndex: Int, transform: (Track) -> Track): Project {
    if (trackIndex !in tracks.indices) {
        return this
    }

    return copy(tracks = tracks.mapIndexed { index, track -> if (index == trackIndex) transform(track) else track })
}

/** Adds an empty track at the end, unless the rack has no room for one. A blank [name] is numbered. */
fun Project.addTrack(name: String = ""): Project {
    if (tracks.size >= Project.MAX_TRACKS) {
        return this
    }

    return copy(tracks = tracks + Track(name.ifBlank { "Track ${tracks.size + 1}" }))
}

/** Removes a track and its clips; the project keeps at least one. The tracks after it move up. */
fun Project.removeTrack(trackIndex: Int): Project {
    if (tracks.size <= 1 || trackIndex !in tracks.indices) {
        return this
    }

    return copy(tracks = tracks.filterIndexed { index, _ -> index != trackIndex })
}

fun Project.renameTrack(trackIndex: Int, name: String): Project {
    return updateTrack(trackIndex) { it.copy(name = name) }
}

/** Seeds the chance of notes (0 to [Project.MAX_PROBABILITY_SEED]): another seed is another variation of the song. */
fun Project.setProbabilitySeed(seed: Int): Project {
    return copy(probabilitySeed = seed.coerceIn(0, Project.MAX_PROBABILITY_SEED))
}

/** Sets the shortest the song can be; it still grows to hold the last placement. */
fun Project.setMinLengthBars(bars: Int): Project {
    return copy(minLengthBars = bars.coerceIn(Project.MIN_LENGTH_BARS, Project.MAX_LENGTH_BARS))
}

/** Adds an empty clip to the track, with the id [Track.nextClipId] had. A blank [name] is numbered. */
fun Project.addClip(trackIndex: Int, lengthBars: Int, name: String = ""): Project {
    return updateTrack(trackIndex) { track ->
        val id = track.nextClipId
        val clip = Clip(
            id = id,
            name = name.ifBlank { "Clip $id" },
            lengthBars = lengthBars.coerceIn(Clip.MIN_LENGTH_BARS, Project.MAX_LENGTH_BARS)
        )

        track.copy(clips = track.clips + clip)
    }
}

/** Applies [transform] to a clip of the track. */
fun Project.updateClip(trackIndex: Int, clipId: Int, transform: (Clip) -> Clip): Project {
    return updateTrack(trackIndex) { track ->
        track.copy(clips = track.clips.map { clip -> if (clip.id == clipId) transform(clip) else clip })
    }
}

fun Project.renameClip(trackIndex: Int, clipId: Int, name: String): Project {
    return updateClip(trackIndex, clipId) { it.copy(name = name) }
}

/** Edits the notes of a clip: [edit] gets them and the clip's length in ticks (see [ClipEditing]). */
fun Project.editNotes(
    trackIndex: Int,
    clipId: Int,
    edit: (notes: List<ClipNote>, lengthTicks: Int) -> List<ClipNote>
): Project {
    return updateClip(trackIndex, clipId) { clip -> clip.copy(notes = edit(clip.notes, clip.lengthTicks)) }
}

/** Removes a clip, and every placement of it. */
fun Project.removeClip(trackIndex: Int, clipId: Int): Project {
    return updateTrack(trackIndex) { track ->
        track.copy(
            clips = track.clips.filter { it.id != clipId },
            placements = track.placements.filter { it.clipId != clipId }
        )
    }
}

/** Adds a copy of a clip to the track, not placed anywhere. */
fun Project.duplicateClip(trackIndex: Int, clipId: Int): Project {
    return updateTrack(trackIndex) { track ->
        val original = track.clip(clipId)

        if (original == null) {
            track
        } else {
            track.copy(clips = track.clips + original.copy(id = track.nextClipId, name = "${original.name} copy"))
        }
    }
}

/**
 * Sets the length of a clip, cutting the notes that no longer fit. Refused (the project is returned
 * as it was) if the clip is placed where it would then overlap the next placement.
 */
fun Project.setClipLength(trackIndex: Int, clipId: Int, lengthBars: Int): Project {
    val track = tracks.getOrNull(trackIndex) ?: return this
    val clip = track.clip(clipId) ?: return this
    val resizedClip = ClipEditing.fitToLength(clip, lengthBars)
    val resizedTrack = track.copy(clips = track.clips.map { if (it.id == clipId) resizedClip else it })
    val allFit = resizedTrack.placements
        .filter { it.clipId == clipId }
        .all { resizedTrack.canPlace(clipId, it.startBar, it.id) }

    if (!allFit) {
        return this
    }

    return updateTrack(trackIndex) { resizedTrack }
}

/** Places a clip of the track from [startBar]. Null if it does not fit there (see [Track.canPlace]). */
fun Project.placeClip(trackIndex: Int, clipId: Int, startBar: Int): Project? {
    val track = tracks.getOrNull(trackIndex) ?: return null

    if (!track.canPlace(clipId, startBar)) {
        return null
    }

    return updateTrack(trackIndex) {
        val placement = Placement(it.nextPlacementId, clipId, startBar)
        it.copy(placements = (it.placements + placement).sortedBy { added -> added.startBar })
    }
}

/** Moves a placement to [startBar]. Null if the placement is unknown or does not fit there. */
fun Project.movePlacement(trackIndex: Int, placementId: Int, startBar: Int): Project? {
    val track = tracks.getOrNull(trackIndex) ?: return null
    val placement = track.placements.firstOrNull { it.id == placementId } ?: return null

    if (!track.canPlace(placement.clipId, startBar, placementId)) {
        return null
    }

    return updateTrack(trackIndex) { current ->
        val moved = current.placements.map { if (it.id == placementId) it.copy(startBar = startBar) else it }
        current.copy(placements = moved.sortedBy { it.startBar })
    }
}

/**
 * Moves a placement to [startBar] of the track [toTrack]. Within its own track that is [movePlacement]. To
 * another track, the clip goes along as a copy, since the clips of a track belong to it and play on its
 * instrument: the placement leaves its track, and the clip with it unless another placement of the track
 * still uses it; the other track gets a clip with the same notes and the placement. Null if the placement or
 * the track is unknown, or the clip does not fit there (it would overlap another, or leave the song).
 */
fun Project.movePlacementToTrack(fromTrack: Int, placementId: Int, toTrack: Int, startBar: Int): Project? {
    if (fromTrack == toTrack) {
        return movePlacement(fromTrack, placementId, startBar)
    }

    val source = tracks.getOrNull(fromTrack) ?: return null
    val target = tracks.getOrNull(toTrack) ?: return null
    val placement = source.placements.firstOrNull { it.id == placementId } ?: return null
    val clip = source.clip(placement.clipId) ?: return null
    val endBar = startBar + clip.lengthBars

    if (startBar < 0 || endBar > Project.MAX_LENGTH_BARS) {
        return null
    }

    if (target.placements.any { other -> startBar < target.endBarOf(other) && other.startBar < endBar }) {
        return null
    }

    val isStillUsed = source.placements.any { it.id != placementId && it.clipId == clip.id }
    val copyId = target.nextClipId

    return updateTrack(fromTrack) { current ->
        current.copy(
            placements = current.placements.filter { it.id != placementId },
            clips = if (isStillUsed) current.clips else current.clips.filter { it.id != clip.id }
        )
    }.updateTrack(toTrack) { current ->
        current.copy(
            clips = current.clips + clip.copy(id = copyId),
            placements = (current.placements + Placement(current.nextPlacementId, copyId, startBar)).sortedBy { it.startBar }
        )
    }
}

fun Project.removePlacement(trackIndex: Int, placementId: Int): Project {
    return updateTrack(trackIndex) { track ->
        track.copy(placements = track.placements.filter { it.id != placementId })
    }
}

/** Places another instance of a placement's clip right after it. Null if there is no room. */
fun Project.duplicatePlacement(trackIndex: Int, placementId: Int): Project? {
    val track = tracks.getOrNull(trackIndex) ?: return null
    val placement = track.placements.firstOrNull { it.id == placementId } ?: return null

    return placeClip(trackIndex, placement.clipId, track.endBarOf(placement))
}

/**
 * Places a clip like [placeClip], but takes the room it needs: the placements it would overlap are
 * removed (their clips stay). Null if the clip cannot be placed there at all: an unknown track or
 * clip, or a place outside the song.
 */
fun Project.placeClipReplacing(trackIndex: Int, clipId: Int, startBar: Int): Project? {
    val track = tracks.getOrNull(trackIndex) ?: return null
    val clip = track.clip(clipId) ?: return null
    val endBar = startBar + clip.lengthBars

    if (startBar < 0 || endBar > Project.MAX_LENGTH_BARS) {
        return null
    }

    val cleared = updateTrack(trackIndex) { current ->
        current.copy(placements = current.placements.filter { current.endBarOf(it) <= startBar || it.startBar >= endBar })
    }

    return cleared.placeClip(trackIndex, clipId, startBar)
}

/**
 * Adds a clip with [notes] to the track and places it from [startBar], over what was there (see
 * [placeClipReplacing]). Null, changing nothing, if it cannot be placed.
 */
fun Project.addRecordedClip(trackIndex: Int, startBar: Int, lengthBars: Int, notes: List<ClipNote>, name: String): Project? {
    val track = tracks.getOrNull(trackIndex) ?: return null
    val id = track.nextClipId
    val clip = Clip(id, name, lengthBars.coerceIn(Clip.MIN_LENGTH_BARS, Project.MAX_LENGTH_BARS), notes)
    val withClip = updateTrack(trackIndex) { it.copy(clips = it.clips + clip) }

    return withClip.placeClipReplacing(trackIndex, id, startBar)
}

/** Empties the song: every track loses its clips and placements, and keeps its name and mix settings. */
fun Project.clearSong(): Project {
    return copy(tracks = tracks.map { it.copy(clips = emptyList(), placements = emptyList()) })
}

/**
 * The project with at least [count] tracks (never more than the rack has room for): empty tracks are
 * added at the end. Tracks are never removed. The rack always has all its tracks, and so does a project
 * played on it.
 */
fun Project.withTrackCount(count: Int): Project {
    val wanted = count.coerceAtMost(Project.MAX_TRACKS)

    if (tracks.size >= wanted) {
        return this
    }

    return copy(tracks = tracks + (tracks.size until wanted).map { Track("Track ${it + 1}") })
}

/**
 * The project with the level, pan, mute and solo of each track taken from [source]: for going back to an
 * earlier project (undo) without taking the mix of its time, which is not part of what is undone.
 */
fun Project.withMixOf(source: Project): Project {
    return copy(
        tracks = tracks.mapIndexed { index, track ->
            val current = source.tracks.getOrNull(index)

            if (current == null) {
                track
            } else {
                track.copy(levelDb = current.levelDb, pan = current.pan, isMuted = current.isMuted, isSoloed = current.isSoloed)
            }
        }
    )
}
