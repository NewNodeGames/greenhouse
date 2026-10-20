package org.androidaudioplugin.greenhouse.data

import android.util.Log
import org.androidaudioplugin.greenhouse.core.SequencerSettings
import org.androidaudioplugin.greenhouse.project.Clip
import org.androidaudioplugin.greenhouse.project.ClipNoteCodec
import org.androidaudioplugin.greenhouse.project.Placement
import org.androidaudioplugin.greenhouse.project.Project
import org.androidaudioplugin.greenhouse.project.Track
import org.androidaudioplugin.greenhouse.project.sanitized
import org.json.JSONArray
import org.json.JSONObject

/**
 * Serializes and deserializes [RackPreset] instances to and from JSON format (.ghrack).
 */
object RackStateSerializer {
    private const val TAG = "RackStateSerializer"

    private const val KEY_FORMAT = "format"
    private const val KEY_VERSION = "version"
    private const val KEY_NAME = "name"
    private const val KEY_CREATED_AT = "createdAt"
    private const val KEY_MODIFIED_AT = "modifiedAt"
    private const val KEY_SLOTS = "slots"
    private const val KEY_SLOTS_PER_TRACK = "slotsPerTrack"
    private const val KEY_CUSTOM_PROPERTIES = "customProperties"

    private const val KEY_SLOT_INDEX = "slotIndex"
    private const val KEY_SLOT_TYPE = "slotType"
    private const val KEY_SOURCE = "source"
    private const val KEY_PLUGIN_ID = "pluginId"
    private const val KEY_PACKAGE_NAME = "packageName"
    private const val KEY_DISPLAY_NAME = "displayName"
    private const val KEY_IS_BYPASSED = "isBypassed"
    private const val KEY_LEVEL_DB = "levelDb"
    private const val KEY_MIX = "mix"
    private const val KEY_SELECTED_PRESET_INDEX = "selectedPresetIndex"
    private const val KEY_STATE_DATA_BASE64 = "stateDataBase64"
    private const val KEY_PARAMETERS = "parameters"

    private const val KEY_SEQUENCE = "sequence"
    private const val KEY_BPM = "bpm"
    private const val KEY_LENGTH_BARS = "lengthBars"
    private const val KEY_IS_QUANTIZING = "isQuantizing"
    private const val KEY_QUANTIZE_TICKS = "quantizeTicks"
    private const val KEY_METRONOME_LEVEL_DB = "metronomeLevelDb"
    private const val KEY_EVENTS_BASE64 = "eventsBase64"
    private const val KEY_AUTO_LENGTH_BARS = "autoLengthBars"

    private const val KEY_PROJECT = "project"
    private const val KEY_MIN_LENGTH_BARS = "minLengthBars"
    private const val KEY_TRACKS = "tracks"
    private const val KEY_PAN = "pan"
    private const val KEY_IS_MUTED = "isMuted"
    private const val KEY_IS_SOLOED = "isSoloed"
    private const val KEY_CLIPS = "clips"
    private const val KEY_PLACEMENTS = "placements"
    private const val KEY_ID = "id"
    private const val KEY_CLIP_ID = "clipId"
    private const val KEY_START_BAR = "startBar"
    private const val KEY_NOTES_BASE64 = "notesBase64"

    fun serializeToJson(preset: RackPreset): String {
        val root = JSONObject()

        root.put(KEY_FORMAT, RackPreset.FORMAT_IDENTIFIER)
        root.put(KEY_VERSION, preset.version)
        root.put(KEY_NAME, preset.name)
        root.put(KEY_CREATED_AT, preset.createdAt)
        root.put(KEY_MODIFIED_AT, preset.modifiedAt)

        val slotsArray = JSONArray()

        for (slot in preset.slots) {
            val slotObj = JSONObject()
            slotObj.put(KEY_SLOT_INDEX, slot.slotIndex)
            slotObj.put(KEY_SLOT_TYPE, slot.slotType)
            slotObj.put(KEY_SOURCE, slot.source)
            slotObj.put(KEY_PLUGIN_ID, slot.pluginId ?: JSONObject.NULL)
            slotObj.put(KEY_PACKAGE_NAME, slot.packageName ?: JSONObject.NULL)
            slotObj.put(KEY_DISPLAY_NAME, slot.displayName ?: JSONObject.NULL)
            slotObj.put(KEY_IS_BYPASSED, slot.isBypassed)
            slotObj.put(KEY_LEVEL_DB, slot.levelDb.toDouble())
            slotObj.put(KEY_MIX, slot.mix.toDouble())
            slotObj.put(KEY_SELECTED_PRESET_INDEX, slot.selectedPresetIndex)
            slotObj.put(KEY_STATE_DATA_BASE64, slot.stateDataBase64 ?: JSONObject.NULL)

            val paramsObj = JSONObject()

            for ((paramId, value) in slot.parameters) {
                paramsObj.put(paramId.toString(), value)
            }

            slotObj.put(KEY_PARAMETERS, paramsObj)

            val slotCustomObj = JSONObject()

            for ((key, value) in slot.customProperties) {
                slotCustomObj.put(key, value)
            }

            slotObj.put(KEY_CUSTOM_PROPERTIES, slotCustomObj)

            slotsArray.put(slotObj)
        }

        root.put(KEY_SLOTS, slotsArray)

        val slotsPerTrack = preset.slotsPerTrack

        if (slotsPerTrack != null) {
            root.put(KEY_SLOTS_PER_TRACK, slotsPerTrack)
        }

        val sequence = preset.sequence

        if (sequence != null) {
            root.put(KEY_SEQUENCE, serializeSequence(sequence))
        }

        val project = preset.project

        if (project != null) {
            root.put(KEY_PROJECT, serializeProject(project))
        }

        val customPropsObj = JSONObject()

        for ((key, value) in preset.customProperties) {
            customPropsObj.put(key, value)
        }

        root.put(KEY_CUSTOM_PROPERTIES, customPropsObj)

        return root.toString(2)
    }

    fun deserializeFromJson(jsonString: String): RackPreset? {
        return try {
            val root = JSONObject(jsonString)

            val name = root.optString(KEY_NAME, "Untitled Preset")
            val version = root.optInt(KEY_VERSION, RackPreset.CURRENT_SCHEMA_VERSION)
            val createdAt = root.optLong(KEY_CREATED_AT, System.currentTimeMillis())
            val modifiedAt = root.optLong(KEY_MODIFIED_AT, System.currentTimeMillis())

            val slotsList = mutableListOf<SlotState>()
            val slotsArray = root.optJSONArray(KEY_SLOTS)

            if (slotsArray != null) {
                for (i in 0 until slotsArray.length()) {
                    val slotObj = slotsArray.optJSONObject(i)

                    if (slotObj != null) {
                        val slotIndex = slotObj.optInt(KEY_SLOT_INDEX, i)
                        val slotType = slotObj.optString(KEY_SLOT_TYPE, if (slotIndex == 0) SlotType.INSTRUMENT else SlotType.EFFECT)
                        // Absent from sessions saved before there were several device sources
                        val source = slotObj.optString(KEY_SOURCE, SlotState.DEFAULT_SOURCE).ifBlank { SlotState.DEFAULT_SOURCE }
                        val pluginId = if (slotObj.has(KEY_PLUGIN_ID) && !slotObj.isNull(KEY_PLUGIN_ID)) {
                            slotObj.optString(KEY_PLUGIN_ID).ifBlank { null }
                        } else {
                            null
                        }

                        val packageName = if (slotObj.has(KEY_PACKAGE_NAME) && !slotObj.isNull(KEY_PACKAGE_NAME)) {
                            slotObj.optString(KEY_PACKAGE_NAME).ifBlank { null }
                        } else {
                            null
                        }

                        val displayName = if (slotObj.has(KEY_DISPLAY_NAME) && !slotObj.isNull(KEY_DISPLAY_NAME)) {
                            slotObj.optString(KEY_DISPLAY_NAME).ifBlank { null }
                        } else {
                            null
                        }

                        val isBypassed = slotObj.optBoolean(KEY_IS_BYPASSED, false)
                        // Absent from sessions saved before the host level and mix existed
                        val levelDb = slotObj.optDouble(KEY_LEVEL_DB, SlotHostSettings.DEFAULT_LEVEL_DB.toDouble())
                            .toFloat()
                            .coerceIn(SlotHostSettings.MIN_LEVEL_DB, SlotHostSettings.MAX_LEVEL_DB)
                        val mix = slotObj.optDouble(KEY_MIX, SlotHostSettings.INITIAL_MIX.toDouble())
                            .toFloat()
                            .coerceIn(SlotHostSettings.DRY_ONLY_MIX, SlotHostSettings.WET_ONLY_MIX)
                        val selectedPresetIndex = slotObj.optInt(KEY_SELECTED_PRESET_INDEX, -1)
                        val stateDataBase64 = if (slotObj.has(KEY_STATE_DATA_BASE64) && !slotObj.isNull(KEY_STATE_DATA_BASE64)) {
                            slotObj.optString(KEY_STATE_DATA_BASE64).ifBlank { null }
                        } else {
                            null
                        }

                        val paramsMap = mutableMapOf<Int, Double>()
                        val paramsObj = slotObj.optJSONObject(KEY_PARAMETERS)

                        if (paramsObj != null) {
                            val keys = paramsObj.keys()

                            while (keys.hasNext()) {
                                val keyStr = keys.next()
                                val paramId = keyStr.toIntOrNull()

                                if (paramId != null) {
                                    paramsMap[paramId] = paramsObj.optDouble(keyStr, 0.0)
                                }
                            }
                        }

                        val slotCustomMap = mutableMapOf<String, String>()
                        val slotCustomObj = slotObj.optJSONObject(KEY_CUSTOM_PROPERTIES)

                        if (slotCustomObj != null) {
                            val keys = slotCustomObj.keys()

                            while (keys.hasNext()) {
                                val key = keys.next()
                                slotCustomMap[key] = slotCustomObj.optString(key, "")
                            }
                        }

                        slotsList.add(
                            SlotState(
                                slotIndex = slotIndex,
                                slotType = slotType,
                                source = source,
                                pluginId = pluginId,
                                packageName = packageName,
                                displayName = displayName,
                                isBypassed = isBypassed,
                                levelDb = levelDb,
                                mix = mix,
                                selectedPresetIndex = selectedPresetIndex,
                                stateDataBase64 = stateDataBase64,
                                parameters = paramsMap,
                                customProperties = slotCustomMap
                            )
                        )
                    }
                }
            }

            val customPropsMap = mutableMapOf<String, String>()
            val customPropsObj = root.optJSONObject(KEY_CUSTOM_PROPERTIES)

            if (customPropsObj != null) {
                val keys = customPropsObj.keys()

                while (keys.hasNext()) {
                    val key = keys.next()
                    customPropsMap[key] = customPropsObj.optString(key, "")
                }
            }

            RackPreset(
                name = name,
                version = version,
                createdAt = createdAt,
                modifiedAt = modifiedAt,
                slots = slotsList,
                sequence = root.optJSONObject(KEY_SEQUENCE)?.let { deserializeSequence(it) },
                customProperties = customPropsMap,
                project = root.optJSONObject(KEY_PROJECT)?.let { deserializeProject(it) },
                slotsPerTrack = if (root.has(KEY_SLOTS_PER_TRACK)) root.optInt(KEY_SLOTS_PER_TRACK) else null
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to deserialize RackPreset from JSON", e)
            null
        }
    }

    private fun serializeSequence(sequence: SequenceState): JSONObject {
        val settings = sequence.settings
        val sequenceObj = JSONObject()
        sequenceObj.put(KEY_BPM, settings.bpm)
        sequenceObj.put(KEY_LENGTH_BARS, settings.lengthBars)
        sequenceObj.put(KEY_IS_QUANTIZING, settings.isQuantizing)
        sequenceObj.put(KEY_QUANTIZE_TICKS, settings.quantizeTicks)
        sequenceObj.put(KEY_METRONOME_LEVEL_DB, settings.metronomeLevelDb.toDouble())
        sequenceObj.put(KEY_EVENTS_BASE64, sequence.eventsBase64 ?: JSONObject.NULL)
        sequenceObj.put(KEY_AUTO_LENGTH_BARS, sequence.autoLengthBars)
        return sequenceObj
    }

    /** Out of range values are clamped by the engine when the sequence is restored. */
    private fun deserializeSequence(sequenceObj: JSONObject): SequenceState {
        val defaults = SequencerSettings()
        val settings = SequencerSettings(
            bpm = sequenceObj.optDouble(KEY_BPM, defaults.bpm),
            lengthBars = sequenceObj.optInt(KEY_LENGTH_BARS, defaults.lengthBars),
            isQuantizing = sequenceObj.optBoolean(KEY_IS_QUANTIZING, defaults.isQuantizing),
            quantizeTicks = sequenceObj.optInt(KEY_QUANTIZE_TICKS, defaults.quantizeTicks),
            metronomeLevelDb = sequenceObj.optDouble(KEY_METRONOME_LEVEL_DB, defaults.metronomeLevelDb.toDouble()).toFloat()
        )
        val eventsBase64 = if (sequenceObj.isNull(KEY_EVENTS_BASE64)) {
            null
        } else {
            sequenceObj.optString(KEY_EVENTS_BASE64).ifBlank { null }
        }

        return SequenceState(settings, eventsBase64, sequenceObj.optInt(KEY_AUTO_LENGTH_BARS, 0))
    }

    private fun serializeProject(project: Project): JSONObject {
        val projectObj = JSONObject()
        projectObj.put(KEY_MIN_LENGTH_BARS, project.minLengthBars)

        val tracksArray = JSONArray()

        for (track in project.tracks) {
            val trackObj = JSONObject()
            trackObj.put(KEY_NAME, track.name)
            trackObj.put(KEY_LEVEL_DB, track.levelDb.toDouble())
            trackObj.put(KEY_PAN, track.pan.toDouble())
            trackObj.put(KEY_IS_MUTED, track.isMuted)
            trackObj.put(KEY_IS_SOLOED, track.isSoloed)

            val clipsArray = JSONArray()

            for (clip in track.clips) {
                val clipObj = JSONObject()
                clipObj.put(KEY_ID, clip.id)
                clipObj.put(KEY_NAME, clip.name)
                clipObj.put(KEY_LENGTH_BARS, clip.lengthBars)
                clipObj.put(KEY_NOTES_BASE64, SequenceState.encodeEvents(ClipNoteCodec.pack(clip.notes)) ?: JSONObject.NULL)
                clipsArray.put(clipObj)
            }

            trackObj.put(KEY_CLIPS, clipsArray)

            val placementsArray = JSONArray()

            for (placement in track.placements) {
                val placementObj = JSONObject()
                placementObj.put(KEY_ID, placement.id)
                placementObj.put(KEY_CLIP_ID, placement.clipId)
                placementObj.put(KEY_START_BAR, placement.startBar)
                placementsArray.put(placementObj)
            }

            trackObj.put(KEY_PLACEMENTS, placementsArray)
            tracksArray.put(trackObj)
        }

        projectObj.put(KEY_TRACKS, tracksArray)
        return projectObj
    }

    /** What is read can be anything: the project is made consistent ([sanitized]) before it is used. */
    private fun deserializeProject(projectObj: JSONObject): Project {
        val tracks = mutableListOf<Track>()
        val tracksArray = projectObj.optJSONArray(KEY_TRACKS)

        if (tracksArray != null) {
            for (i in 0 until tracksArray.length()) {
                val trackObj = tracksArray.optJSONObject(i) ?: continue
                tracks.add(deserializeTrack(trackObj, i))
            }
        }

        val defaults = Project()
        val minLengthBars = projectObj.optInt(KEY_MIN_LENGTH_BARS, defaults.minLengthBars)
        return Project(tracks.ifEmpty { defaults.tracks }, minLengthBars).sanitized()
    }

    private fun deserializeTrack(trackObj: JSONObject, index: Int): Track {
        val clips = mutableListOf<Clip>()
        val clipsArray = trackObj.optJSONArray(KEY_CLIPS)

        if (clipsArray != null) {
            for (i in 0 until clipsArray.length()) {
                val clipObj = clipsArray.optJSONObject(i) ?: continue
                val notesBase64 = if (clipObj.isNull(KEY_NOTES_BASE64)) {
                    null
                } else {
                    clipObj.optString(KEY_NOTES_BASE64).ifBlank { null }
                }

                clips.add(
                    Clip(
                        id = clipObj.optInt(KEY_ID, i + 1),
                        name = clipObj.optString(KEY_NAME, ""),
                        lengthBars = clipObj.optInt(KEY_LENGTH_BARS, 1),
                        notes = ClipNoteCodec.unpack(SequenceState.decodeEvents(notesBase64))
                    )
                )
            }
        }

        val placements = mutableListOf<Placement>()
        val placementsArray = trackObj.optJSONArray(KEY_PLACEMENTS)

        if (placementsArray != null) {
            for (i in 0 until placementsArray.length()) {
                val placementObj = placementsArray.optJSONObject(i) ?: continue

                placements.add(
                    Placement(
                        id = placementObj.optInt(KEY_ID, i + 1),
                        clipId = placementObj.optInt(KEY_CLIP_ID, 0),
                        startBar = placementObj.optInt(KEY_START_BAR, 0)
                    )
                )
            }
        }

        return Track(
            name = trackObj.optString(KEY_NAME, "Track ${index + 1}"),
            clips = clips,
            placements = placements,
            levelDb = trackObj.optDouble(KEY_LEVEL_DB, SlotHostSettings.DEFAULT_LEVEL_DB.toDouble()).toFloat(),
            pan = trackObj.optDouble(KEY_PAN, Track.CENTER_PAN.toDouble()).toFloat(),
            isMuted = trackObj.optBoolean(KEY_IS_MUTED, false),
            isSoloed = trackObj.optBoolean(KEY_IS_SOLOED, false)
        )
    }
}
