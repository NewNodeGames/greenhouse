// Desktop tests of the native MIDI sequencer and Standard MIDI File conversion. See CMakeLists.txt.

#include "sequencer/MidiSequencer.h"
#include "sequencer/StandardMidiFile.h"
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <map>
#include <set>
#include <vector>

using namespace greenhouse;

namespace
{

constexpr int32_t SAMPLE_RATE = 48000;
constexpr int32_t BLOCK_FRAMES = 256;
constexpr int64_t BAR = SEQUENCER_TICKS_PER_BAR;
constexpr int64_t BEAT = SEQUENCER_PPQ;
// JR timestamps count 1/31250 s: event positions read back are exact to about 1.5 frames at 48 kHz
constexpr int64_t FRAME_TOLERANCE = 2;
constexpr uint32_t MIDI2_NOTE_ON = 0x40900000u;
constexpr uint32_t MIDI2_NOTE_OFF = 0x40800000u;
constexpr uint32_t MIDI2_CONTROL_CHANGE = 0x40B00000u;
constexpr uint32_t FULL_VELOCITY = 0xFFFF0000u;
constexpr uint32_t HALF_VALUE = 0x80000000u;
constexpr uint32_t CC_VOLUME = 7;
constexpr uint32_t CC_ALL_NOTES_OFF = 123;

int gFailures = 0;

void check(bool condition, const char* description, int line)
{
    if (!condition) {
        std::printf("FAIL line %d: %s\n", line, description);
        gFailures++;
    }
}

#define CHECK(condition) check((condition), #condition, __LINE__)

ump::Packet makePacket(uint32_t word0, uint32_t word1)
{
    ump::Packet packet;
    packet.mWords[0] = word0;
    packet.mWords[1] = word1;
    packet.mWordCount = ump::getWordCount(word0);
    return packet;
}

ump::Packet noteOn(uint32_t note)
{
    return makePacket(MIDI2_NOTE_ON | (note << ump::NOTE_SHIFT), FULL_VELOCITY);
}

ump::Packet noteOff(uint32_t note)
{
    return makePacket(MIDI2_NOTE_OFF | (note << ump::NOTE_SHIFT), 0);
}

bool isNoteOn(uint32_t word0)
{
    return ump::getStatus(word0) == ump::STATUS_NOTE_ON;
}

SequenceEvent event(int64_t tick, const ump::Packet& packet, int32_t take = 0)
{
    return SequenceEvent{tick, 0, take, 0, packet};
}

/** A sequencer driven block by block, as the engine does, with slot 0's events read back as the plugin gets them. */
class Harness
{
public:
    struct Played
    {
        int64_t mFrame;
        uint32_t mWord0;
    };

    OboeEngine mEngine;
    MidiSequencer mSequencer{mEngine};
    int64_t mFrame{0};

    explicit Harness(int32_t lengthBars = 1)
    {
        SequencerSettings settings;
        settings.mLengthBars = lengthBars;
        mSequencer.setSettings(settings);
    }

    void send(const ump::Packet& packet, int32_t track = 0)
    {
        mSequencer.recordInput(track, reinterpret_cast<const uint8_t*>(packet.mWords.data()), packet.mWordCount * ump::BYTES_PER_WORD);
    }

    /** Renders blocks, polling the status as the UI does (it publishes what was recorded). Returns what a track's instrument plays. */
    std::vector<Played> run(int32_t blocks, int32_t track = 0)
    {
        std::vector<Played> played;
        std::vector<float> audio(BLOCK_FRAMES * STEREO_CHANNEL_COUNT);

        for (int32_t block = 0; block < blocks; block++) {
            mSequencer.getStatus();
            mSequencer.beginBlock(BLOCK_FRAMES, SAMPLE_RATE);
            readSlotEvents(played, track);
            mSequencer.renderMetronome(audio.data(), BLOCK_FRAMES);
            mFrame += BLOCK_FRAMES;
        }

        return played;
    }

    std::vector<Played> runTicks(int64_t ticks, double bpm = DEFAULT_SEQUENCER_BPM)
    {
        auto frames = static_cast<double>(ticks) * SAMPLE_RATE * SECONDS_PER_MINUTE / (bpm * SEQUENCER_PPQ);
        return run(static_cast<int32_t>(frames / BLOCK_FRAMES));
    }

    void readSlotEvents(std::vector<Played>& played, int32_t track = 0) const
    {
        int32_t wordCount = 0;
        auto words = mSequencer.getSlotEvents(track, wordCount);
        uint32_t jrTicks = 0;

        for (int32_t i = 0; i < wordCount; i += ump::getWordCount(words[i])) {
            if (ump::getMessageType(words[i]) == 0) {
                jrTicks += words[i] & ump::JR_TIMESTAMP_MAX_TICKS;
                continue;
            }

            played.push_back(Played{mFrame + static_cast<int64_t>(jrTicks) * SAMPLE_RATE / ump::JR_TIMESTAMP_TICKS_PER_SECOND, words[i]});
        }
    }
};

int64_t ticksToFrames(int64_t ticks)
{
    return ticks * SAMPLE_RATE * static_cast<int64_t>(SECONDS_PER_MINUTE) / (static_cast<int64_t>(DEFAULT_SEQUENCER_BPM) * SEQUENCER_PPQ);
}

int32_t countNoteOns(const std::vector<Harness::Played>& played)
{
    auto count = 0;

    for (const auto& event : played) {
        count += isNoteOn(event.mWord0) ? 1 : 0;
    }

    return count;
}

/** Notes whose note-ons outnumber their note-offs after some passes, as a voice-counting synth hears them. */
int32_t countStuckNotes(Harness& harness, int32_t passes)
{
    std::map<uint32_t, int32_t> voices;

    for (int32_t pass = 0; pass < passes; pass++) {
        for (const auto& event : harness.runTicks(BAR)) {
            voices[ump::getNote(event.mWord0)] += isNoteOn(event.mWord0) ? 1 : -1;
        }
    }

    auto stuck = 0;

    for (const auto& voice : voices) {
        stuck += voice.second > 1 ? 1 : 0;
    }

    return stuck;
}

void testArmedAutoLengthTake()
{
    Harness harness(AUTO_LENGTH_BARS);
    auto& sequencer = harness.mSequencer;

    sequencer.startRecording();
    harness.run(1);
    CHECK(sequencer.getStatus().mState == TransportState::ARMED);

    // The first note starts the take; it was heard live, so it is not replayed in this pass
    harness.send(noteOn(60));
    CHECK(harness.run(1).empty());
    CHECK(sequencer.getStatus().mState == TransportState::PLAYING);
    harness.runTicks(BAR + BAR / 2);
    harness.send(noteOff(60));
    harness.runTicks(BAR / 2);
    sequencer.stopRecording();

    // Stopped at ~2 bars: the take sets a 2-bar loop, and the setting stays AUTO
    auto status = sequencer.getStatus();
    CHECK(!status.mIsRecording);
    CHECK(status.mAutoLengthBars == 2 && status.mLengthTicks == 2 * BAR);
    CHECK(sequencer.getSettings().mLengthBars == AUTO_LENGTH_BARS);
    // One note per loop
    CHECK(countNoteOns(harness.runTicks(2 * BAR)) == 1);

    // Overdubs loop over the 2 bars
    sequencer.startRecording();
    harness.send(noteOn(62));
    harness.runTicks(3 * BAR);
    harness.send(noteOff(62));
    sequencer.stopRecording();
    CHECK(sequencer.getStatus().mAutoLengthBars == 2);

    sequencer.stop();
    harness.run(1);
    sequencer.clear();
    CHECK(sequencer.getStatus().mAutoLengthBars == 0);
}

void testTiming()
{
    Harness harness;
    auto& sequencer = harness.mSequencer;
    std::vector<SequenceEvent> events{event(BEAT, noteOn(64)), event(2 * BEAT, noteOff(64))};

    sequencer.setEvents(events, 0);
    harness.run(1);
    harness.mFrame = 0;
    sequencer.startPlayback();
    auto played = harness.runTicks(BAR);
    CHECK(played.size() == 2);
    CHECK(std::llabs(played[0].mFrame - ticksToFrames(BEAT)) <= FRAME_TOLERANCE);
    CHECK(std::llabs(played[1].mFrame - ticksToFrames(2 * BEAT)) <= FRAME_TOLERANCE);

    // Quantized to 1/16: the note start moves to the grid, its end moves with it
    auto sixteenth = BEAT / 4;
    events = {event(BEAT + sixteenth / 2 - 1, noteOn(64)), event(2 * BEAT, noteOff(64))};
    sequencer.setEvents(events, 0);
    SequencerSettings settings = sequencer.getSettings();
    settings.mIsQuantizing = true;
    settings.mQuantizeTicks = static_cast<int32_t>(sixteenth);
    sequencer.setSettings(settings);
    harness.run(1);
    harness.mFrame = 0;
    sequencer.startPlayback();
    played = harness.runTicks(BAR);
    CHECK(played.size() == 2);
    CHECK(std::llabs(played[0].mFrame - ticksToFrames(BEAT)) <= FRAME_TOLERANCE);
    CHECK(std::llabs(played[1].mFrame - ticksToFrames(2 * BEAT - sixteenth / 2 + 1)) <= FRAME_TOLERANCE);
}

void testOverdubAndMetronome()
{
    Harness harness;
    auto& sequencer = harness.mSequencer;

    sequencer.startRecording();
    harness.run(1);
    harness.send(noteOn(50));
    harness.run(1);
    harness.send(noteOff(50));
    harness.runTicks(BEAT);
    harness.send(noteOn(67));
    harness.runTicks(BEAT / 2);
    harness.send(noteOff(67));

    // Recorded in this pass: nothing replayed until the next pass, which plays both notes
    CHECK(harness.runTicks(BAR - 2 * BEAT).empty());
    CHECK(countNoteOns(harness.runTicks(BAR)) == 2);

    // The metronome clicks while recording only
    auto hasClicks = [&harness]() {
        std::vector<float> audio(BLOCK_FRAMES * STEREO_CHANNEL_COUNT);
        auto isAudible = false;

        for (int64_t frames = 0; frames < ticksToFrames(BAR); frames += BLOCK_FRAMES) {
            std::fill(audio.begin(), audio.end(), 0.0f);
            harness.mSequencer.beginBlock(BLOCK_FRAMES, SAMPLE_RATE);
            harness.mSequencer.renderMetronome(audio.data(), BLOCK_FRAMES);
            isAudible = isAudible || std::any_of(audio.begin(), audio.end(), [](float sample) {
                return sample != 0.0f;
            });
        }

        return isAudible;
    };

    CHECK(hasClicks());

    // Muted at the lowest level, without touching the sequence, but marking it modified
    auto settings = sequencer.getSettings();
    auto revision = sequencer.getStatus().mRevision;
    auto eventCount = sequencer.getEvents().size();
    settings.mMetronomeLevelDb = MIN_METRONOME_LEVEL_DB;
    sequencer.setSettings(settings);
    CHECK(!hasClicks());
    CHECK(sequencer.getEvents().size() == eventCount && sequencer.getStatus().mIsRecording);
    CHECK(sequencer.getStatus().mRevision != revision);
    settings.mMetronomeLevelDb = DEFAULT_METRONOME_LEVEL_DB;
    sequencer.setSettings(settings);
    CHECK(hasClicks());

    sequencer.stopRecording();
    harness.run(1);
    CHECK(!hasClicks());

    // Punching out closes the notes still held; notes held before punching in are not recorded
    harness.send(noteOn(70));
    sequencer.startRecording();
    harness.run(2);
    harness.send(noteOn(72));
    harness.run(2);
    sequencer.stop();
    auto events = sequencer.getEvents();
    auto countNote = [&events](uint32_t note) {
        return std::count_if(events.begin(), events.end(), [note](const SequenceEvent& event) {
            return ump::getNote(event.mPacket.mWords[0]) == note;
        });
    };
    CHECK(countNote(72) == 2);
    CHECK(countNote(70) == 0);
}

void testRecordsNotesOnly()
{
    Harness harness;
    auto& sequencer = harness.mSequencer;

    sequencer.startRecording();
    harness.run(1);
    harness.send(noteOn(60));
    harness.run(2);
    harness.send(makePacket(MIDI2_CONTROL_CHANGE | (CC_ALL_NOTES_OFF << ump::NOTE_SHIFT), 0));
    harness.send(makePacket(MIDI2_CONTROL_CHANGE | (CC_VOLUME << ump::NOTE_SHIFT), HALF_VALUE));
    harness.send(noteOff(60));
    CHECK(sequencer.getEvents().size() == 2);

    sequencer.stop();
    harness.run(1);
    sequencer.reset();
    CHECK(sequencer.getEvents().empty() && sequencer.getSettings().mLengthBars == AUTO_LENGTH_BARS);
}

void testStopRecordingWhileArmed()
{
    Harness harness;
    auto& sequencer = harness.mSequencer;

    sequencer.startRecording();
    harness.run(1);
    sequencer.stopRecording();
    harness.run(1);
    CHECK(sequencer.getStatus().mState == TransportState::STOPPED);
}

void testChangingTheLength()
{
    Harness harness(2);
    auto& sequencer = harness.mSequencer;
    // The last note is held across the loop end
    std::vector<SequenceEvent> events{event(1000, noteOn(64)), event(2000, noteOff(64)), event(7000, noteOn(60)), event(100, noteOff(60))};

    // Extending the loop leaves the new bars empty: the held note stops at the previous loop end
    sequencer.setEvents(events, 0);
    SequencerSettings settings = sequencer.getSettings();
    settings.mLengthBars = 4;
    sequencer.setSettings(settings);
    auto extended = sequencer.getEvents();
    CHECK(extended.size() == 4 && extended[3].mTick == 2 * BAR);

    // Changing the length while recording is allowed
    sequencer.startPlayback();
    harness.run(1);
    sequencer.startRecording();
    harness.run(1);
    settings.mLengthBars = 1;
    sequencer.setSettings(settings);
    harness.run(4);
    harness.send(noteOn(62));
    harness.run(2);
    harness.send(noteOff(62));
    sequencer.stopRecording();
    auto recorded = sequencer.getEvents();
    CHECK(recorded.size() == 6 && recorded[4].mTick < BAR);

    sequencer.startPlayback();
    auto played = harness.runTicks(BAR);
    CHECK(countNoteOns(played) == 2 && played.size() == 4);
}

void testNoStuckNotes()
{
    // The same pitch overlapping itself from two takes
    Harness overlap;
    overlap.mSequencer.setEvents({event(1000, noteOn(60)), event(2000, noteOff(60)), event(1500, noteOn(60), 1), event(2500, noteOff(60), 1)}, 0);
    overlap.run(1);
    overlap.mSequencer.startPlayback();
    CHECK(countStuckNotes(overlap, 4) == 0);

    // Quantized notes of the same pitch from two passes, overlapping in time: paired in recording order
    Harness quantized;
    SequencerSettings settings = quantized.mSequencer.getSettings();
    settings.mIsQuantizing = true;
    settings.mQuantizeTicks = BEAT;
    quantized.mSequencer.setSettings(settings);
    quantized.mSequencer.setEvents({event(1400, noteOn(62)), event(1500, noteOff(62)), event(1300, noteOn(62)), event(2400, noteOff(62))}, 0);
    quantized.run(1);
    quantized.mSequencer.startPlayback();
    CHECK(countStuckNotes(quantized, 4) == 0);

    // A slot too small for a block's events: what does not fit is dropped untracked, notes still end
    Harness small;
    std::vector<SequenceEvent> chord;

    // Only the first note-ons fit; the chord is released highest first, so their note-offs come last
    for (uint32_t note = 60; note < 72; note++) {
        chord.push_back(event(0, noteOn(note)));
    }

    for (uint32_t note = 71; note >= 60; note--) {
        chord.push_back(event(BEAT, noteOff(note)));
    }

    small.mSequencer.setSlotEventCapacity(0, ump::MAX_PACKET_WORDS * 2);
    small.mSequencer.setEvents(chord, 0);
    small.run(1);
    small.mSequencer.startPlayback();
    CHECK(countStuckNotes(small, 4) == 0);
}

void testReleaseSlotNotes()
{
    Harness harness;
    auto& sequencer = harness.mSequencer;

    auto noteEnd = 3 * BEAT;
    sequencer.setEvents({event(0, noteOn(60)), event(noteEnd, noteOff(60))}, 0);
    harness.run(1);
    sequencer.startPlayback();
    harness.run(4);

    // Bypassing the slot: its held note ends now, and its own note-off is not sent again
    sequencer.beginBlock(BLOCK_FRAMES, SAMPLE_RATE);
    sequencer.releaseSlotNotes(0);
    std::vector<Harness::Played> released;
    harness.readSlotEvents(released);
    CHECK(released.size() == 1 && !isNoteOn(released[0].mWord0));
    CHECK(harness.runTicks(noteEnd).empty());
}

void testUndo()
{
    Harness harness;
    auto& sequencer = harness.mSequencer;

    sequencer.startRecording();
    harness.run(1);
    harness.send(noteOn(60));
    harness.run(4);
    harness.send(noteOff(60));
    sequencer.stopRecording();

    // While recording, undo discards what the take recorded, and recording goes on
    sequencer.startRecording();
    harness.run(2);
    harness.send(noteOn(64));
    harness.run(2);
    harness.send(noteOff(64));
    sequencer.undoLastTake();
    CHECK(sequencer.getEvents().size() == 2 && sequencer.getStatus().mIsRecording);

    // Nothing recorded in the take since: undo removes the previous take
    sequencer.undoLastTake();
    CHECK(sequencer.getEvents().empty() && sequencer.getStatus().mIsRecording);

    // Armed and undone: stays armed
    sequencer.stop();
    harness.run(1);
    sequencer.startRecording();
    harness.run(1);
    sequencer.undoLastTake();
    harness.run(1);
    CHECK(sequencer.getStatus().mState == TransportState::ARMED);
}

void testPhrases()
{
    Harness harness(4);
    auto& sequencer = harness.mSequencer;
    auto takeCount = [&sequencer]() {
        std::set<int32_t> takes;

        for (const auto& event : sequencer.getEvents()) {
            takes.insert(event.mTake);
        }

        return takes.size();
    };

    sequencer.startPlayback();
    harness.run(1);
    sequencer.startRecording();

    // A rest shorter than the gap stays in the phrase
    harness.send(noteOn(60));
    harness.runTicks(BEAT);
    harness.send(noteOff(60));
    harness.runTicks(PHRASE_GAP_TICKS / 2);
    harness.send(noteOn(62));
    harness.runTicks(BEAT);
    harness.send(noteOff(62));
    CHECK(takeCount() == 1);

    // After a longer pause, the next note starts a new phrase
    harness.runTicks(PHRASE_GAP_TICKS + BEAT);
    harness.send(noteOn(64));
    harness.runTicks(BEAT);
    harness.send(noteOff(64));
    CHECK(takeCount() == 2);

    // Undo removes the last phrase only, and recording goes on
    sequencer.undoLastTake();
    CHECK(takeCount() == 1 && sequencer.getEvents().size() == 4 && sequencer.getStatus().mIsRecording);

    // The next note continues the (now empty) phrase: no new take for it
    harness.runTicks(PHRASE_GAP_TICKS + BEAT);
    harness.send(noteOn(65));
    harness.runTicks(BEAT);
    harness.send(noteOff(65));
    sequencer.stopRecording();
    CHECK(takeCount() == 2);

    // Undone after recording: the last phrase, then the one before
    sequencer.undoLastTake();
    CHECK(takeCount() == 1 && sequencer.getEvents().size() == 4);
    sequencer.undoLastTake();
    CHECK(sequencer.getEvents().empty());
}

void testUndoingEveryTakeWhileRecordingInAuto()
{
    Harness harness(AUTO_LENGTH_BARS);
    auto& sequencer = harness.mSequencer;

    sequencer.startRecording();
    harness.run(1);
    harness.send(noteOn(60));
    harness.runTicks(4 * BAR);
    harness.send(noteOff(60));
    sequencer.stopRecording();
    CHECK(sequencer.getStatus().mAutoLengthBars == 4);

    // Punched in, then every take undone: the take being recorded sets the length again
    sequencer.startRecording();
    harness.run(1);
    sequencer.undoLastTake();
    harness.runTicks(3 * BAR);
    CHECK(sequencer.getStatus().mLengthTicks > BAR);
}

SequenceEvent trackEvent(int64_t tick, int32_t track, const ump::Packet& packet, int32_t take = 0)
{
    return SequenceEvent{tick, track, take, 0, packet};
}

void testStartPlaybackFromTick()
{
    Harness harness(4);
    auto& sequencer = harness.mSequencer;
    sequencer.setEvents({event(0, noteOn(60)), event(BEAT, noteOff(60)),
                         event(2 * BAR, noteOn(62)), event(2 * BAR + BEAT, noteOff(62))}, 4);
    sequencer.startPlayback(2 * BAR);
    auto played = harness.run(1);

    // It starts at bar 3: the first note is behind it and the second plays at once
    CHECK(countNoteOns(played) == 1);
    CHECK(!played.empty() && ump::getNote(played[0].mWord0) == 62);
    CHECK(sequencer.getPositionTick() >= 2 * BAR && sequencer.getPositionTick() < 2 * BAR + BEAT);
}

void testSeek()
{
    Harness harness(4);
    auto& sequencer = harness.mSequencer;
    sequencer.setEvents({event(0, noteOn(60)), event(BEAT, noteOff(60)),
                         event(2 * BAR, noteOn(62)), event(2 * BAR + BEAT, noteOff(62))}, 4);

    // Stopped: there is no playhead to move
    sequencer.seek(BAR);
    harness.run(1);
    CHECK(sequencer.getStatus().mState == TransportState::STOPPED);
    CHECK(sequencer.getPositionTick() == 0);

    sequencer.startPlayback();
    CHECK(countNoteOns(harness.run(1)) == 1);

    sequencer.seek(2 * BAR);
    auto played = harness.run(1);
    CHECK(sequencer.getStatus().mState == TransportState::PLAYING);
    CHECK(sequencer.getPositionTick() >= 2 * BAR && sequencer.getPositionTick() < 2 * BAR + BEAT);
    // The note held when it jumped ends, then the note at bar 3 starts
    CHECK(played.size() == 2);
    CHECK(played.size() == 2 && !isNoteOn(played[0].mWord0) && ump::getNote(played[0].mWord0) == 60);
    CHECK(played.size() == 2 && isNoteOn(played[1].mWord0) && ump::getNote(played[1].mWord0) == 62);
}

void testReplaceEventsWhilePlaying()
{
    Harness harness(1);
    auto& sequencer = harness.mSequencer;
    // A note held for most of the bar
    sequencer.setEvents({event(0, noteOn(60)), event(BAR - BEAT, noteOff(60))}, 1);
    sequencer.startPlayback();
    harness.runTicks(BAR / 2);
    auto position = sequencer.getPositionTick();
    auto revision = sequencer.getStatus().mRevision;

    CHECK(sequencer.replaceEvents({event(3 * BAR / 4, noteOn(64)), event(3 * BAR / 4 + BEAT / 2, noteOff(64))}, 1));
    CHECK(sequencer.getStatus().mRevision != revision);
    CHECK(sequencer.getStatus().mState == TransportState::PLAYING);
    CHECK(sequencer.getEvents().size() == 2);

    // The edit ends the note it cut off, and the transport carries on from where it was
    auto played = harness.run(1);
    CHECK(played.size() == 1 && !isNoteOn(played[0].mWord0) && ump::getNote(played[0].mWord0) == 60);
    CHECK(sequencer.getPositionTick() > position);

    auto rest = harness.runTicks(BAR / 2);
    CHECK(countNoteOns(rest) == 1);
}

void testReplaceEventsIsRefusedWhileRecording()
{
    Harness harness(1);
    auto& sequencer = harness.mSequencer;
    sequencer.setEvents({event(0, noteOn(60)), event(BEAT, noteOff(60))}, 1);
    sequencer.startRecording();
    harness.run(1);

    CHECK(!sequencer.replaceEvents({}, 1));
    CHECK(sequencer.getEvents().size() == 2);

    sequencer.stopRecording();
    CHECK(sequencer.replaceEvents({}, 1));
    CHECK(sequencer.getEvents().empty());
}

void testTracksPlayTheirOwnEvents()
{
    constexpr int32_t DRUMS = 0;
    constexpr int32_t BASS = 3;
    constexpr int32_t UNUSED = 5;
    Harness harness(1);
    auto& sequencer = harness.mSequencer;
    sequencer.setEvents({trackEvent(0, DRUMS, noteOn(36)), trackEvent(BEAT, DRUMS, noteOff(36)),
                         trackEvent(0, BASS, noteOn(40)), trackEvent(BEAT, BASS, noteOff(40)),
                         trackEvent(BEAT, BASS, noteOn(43)), trackEvent(2 * BEAT, BASS, noteOff(43))}, 1);
    sequencer.startPlayback();

    // The tracks share the transport: render the same blocks and read each track's events
    std::vector<Harness::Played> drums;
    std::vector<Harness::Played> bass;
    std::vector<Harness::Played> unused;
    std::vector<float> audio(BLOCK_FRAMES * STEREO_CHANNEL_COUNT);
    auto blocks = static_cast<int32_t>(ticksToFrames(BAR) / BLOCK_FRAMES);

    for (int32_t block = 0; block < blocks; block++) {
        sequencer.getStatus();
        sequencer.beginBlock(BLOCK_FRAMES, SAMPLE_RATE);
        harness.readSlotEvents(drums, DRUMS);
        harness.readSlotEvents(bass, BASS);
        harness.readSlotEvents(unused, UNUSED);
        sequencer.renderMetronome(audio.data(), BLOCK_FRAMES);
        harness.mFrame += BLOCK_FRAMES;
    }

    CHECK(countNoteOns(drums) == 1 && drums.size() == 2);
    CHECK(countNoteOns(bass) == 2 && bass.size() == 4);
    CHECK(unused.empty());

    for (const auto& played : drums) {
        CHECK(ump::getNote(played.mWord0) == 36);
    }
}

void testRecordsEveryTrack()
{
    Harness harness(AUTO_LENGTH_BARS);
    auto& sequencer = harness.mSequencer;
    sequencer.startRecording();
    harness.run(1);

    harness.send(noteOn(60), 0);
    harness.run(1);
    harness.send(noteOn(64), 2);
    harness.runTicks(BEAT);
    harness.send(noteOff(60), 0);
    harness.send(noteOff(64), 2);
    // A track the rack does not have is not recorded
    harness.send(noteOn(70), MAX_SEQUENCER_SLOTS);
    sequencer.stopRecording();

    auto events = sequencer.getEvents();
    std::map<int32_t, int32_t> perTrack;

    for (const auto& recorded : events) {
        perTrack[recorded.mSlot]++;
    }

    CHECK(events.size() == 4);
    CHECK(perTrack.size() == 2 && perTrack[0] == 2 && perTrack[2] == 2);
}

ump::Packet noteOnWithChance(int32_t note, int32_t percent)
{
    auto packet = noteOn(note);
    setNoteProbability(packet, percent);
    return packet;
}

struct ChanceRun
{
    std::vector<int32_t> mNoteOnsPerPass;
    int32_t mNoteOns{0};
    int32_t mNoteOffs{0};
};

/** Plays a one-bar loop with one note in the middle of it, passes times, and counts what was played. */
ChanceRun playWithChance(uint32_t seed, int32_t percent, int32_t passes)
{
    Harness harness(1);
    harness.mSequencer.setProbabilitySeed(seed);
    harness.mSequencer.setEvents({event(BAR / 2, noteOnWithChance(60, percent)), event(BAR / 2 + BEAT, noteOff(60))}, 1);
    harness.mSequencer.startPlayback();

    ChanceRun run;

    for (int32_t pass = 0; pass < passes; pass++) {
        auto played = harness.runTicks(BAR);
        auto noteOns = countNoteOns(played);

        run.mNoteOnsPerPass.push_back(noteOns);
        run.mNoteOns += noteOns;
        run.mNoteOffs += static_cast<int32_t>(played.size()) - noteOns;
    }

    return run;
}

void testNoteProbabilityIsKeptInThePacket()
{
    auto packet = noteOn(60);

    // Nothing set: the note always plays
    CHECK(getNoteProbability(packet) == 100);

    setNoteProbability(packet, 25);
    CHECK(getNoteProbability(packet) == 25);
    CHECK(packet.mWords[2] != 0);

    // 100 percent is the absence of a setting, which keeps a sequence without chances as it was
    setNoteProbability(packet, 100);
    CHECK(getNoteProbability(packet) == 100);
    CHECK(packet.mWords[2] == 0);

    // 0 percent is a setting, not the absence of one
    setNoteProbability(packet, 0);
    CHECK(getNoteProbability(packet) == 0);
    CHECK(packet.mWords[2] != 0);

    setNoteProbability(packet, 250);
    CHECK(getNoteProbability(packet) == 100);
    setNoteProbability(packet, -5);
    CHECK(getNoteProbability(packet) == 0);
}

void testANoteAlwaysPlaysAtOneHundredPercentAndNeverAtZero()
{
    constexpr int32_t PASSES = 20;

    auto always = playWithChance(1, 100, PASSES);
    CHECK(always.mNoteOns == PASSES);
    CHECK(always.mNoteOffs == PASSES);

    // The note-off of a note that was not played goes with it
    auto never = playWithChance(1, 0, PASSES);
    CHECK(never.mNoteOns == 0);
    CHECK(never.mNoteOffs == 0);
}

void testTheChanceOfANoteFollowsItsPercentage()
{
    constexpr int32_t PASSES = 400;
    constexpr int32_t TOLERANCE_PASSES = 40;
    constexpr int32_t PERCENTS[] = {25, 50, 75};

    for (auto percent : PERCENTS) {
        auto run = playWithChance(7, percent, PASSES);

        CHECK(std::abs(run.mNoteOns - PASSES * percent / 100) <= TOLERANCE_PASSES);
        // Every note played ends, and the ones that were not played never send a note-off
        CHECK(run.mNoteOffs == run.mNoteOns);
    }
}

void testTheSeedMakesTheVariationReproducible()
{
    constexpr int32_t PASSES = 100;

    auto first = playWithChance(5, 50, PASSES);
    auto again = playWithChance(5, 50, PASSES);
    auto other = playWithChance(6, 50, PASSES);

    // The same seed plays the same song loop by loop, another seed another variation
    CHECK(first.mNoteOnsPerPass == again.mNoteOnsPerPass);
    CHECK(first.mNoteOnsPerPass != other.mNoteOnsPerPass);
    // And the loops of a song differ among them: the note is played in some and not in others
    CHECK(first.mNoteOns > 0 && first.mNoteOns < PASSES);
}

void testStandardMidiFile()
{
    constexpr double FILE_BPM = 97.0;
    auto controlChange = makePacket(MIDI2_CONTROL_CHANGE | (CC_VOLUME << ump::NOTE_SHIFT), HALF_VALUE);
    auto bytes = smf::write({event(0, noteOn(60)), event(BEAT / 2, noteOff(60)), event(BEAT, controlChange)}, FILE_BPM, BAR);

    smf::ImportedSequence imported;
    CHECK(smf::read(bytes.data(), static_cast<int32_t>(bytes.size()), MAX_SEQUENCER_SLOTS, imported));
    CHECK(std::fabs(imported.mBpm - FILE_BPM) < 1e-2);
    CHECK(imported.mEvents.size() == 3);

    if (imported.mEvents.size() == 3) {
        CHECK(imported.mEvents[0].mTick == 0 && imported.mEvents[0].mPacket.mWords[1] == FULL_VELOCITY);
        CHECK(imported.mEvents[1].mTick == BEAT / 2 && !isNoteOn(imported.mEvents[1].mPacket.mWords[0]));
        CHECK(imported.mEvents[2].mTick == BEAT && imported.mEvents[2].mPacket.mWords[1] == HALF_VALUE);
    }

    std::vector<uint8_t> junk{1, 2, 3, 4, 5, 6, 7, 8, 9};
    CHECK(!smf::read(junk.data(), static_cast<int32_t>(junk.size()), MAX_SEQUENCER_SLOTS, imported));

    // Imported in AUTO: the loop fits the file
    Harness harness(AUTO_LENGTH_BARS);
    CHECK(harness.mSequencer.importStandardMidiFile(bytes.data(), static_cast<int32_t>(bytes.size())));
    CHECK(harness.mSequencer.getStatus().mLengthTicks == BAR);

    // Exported as it plays: quantized when quantize is on, as recorded otherwise
    constexpr int64_t OFF_GRID_TICK = BEAT + BEAT / 8;
    Harness offGrid;
    offGrid.mSequencer.setEvents({event(OFF_GRID_TICK, noteOn(60)), event(OFF_GRID_TICK + BEAT / 2, noteOff(60))}, 0);
    auto exportedTick = [&offGrid, &imported]() {
        auto exported = offGrid.mSequencer.exportStandardMidiFile();
        return smf::read(exported.data(), static_cast<int32_t>(exported.size()), MAX_SEQUENCER_SLOTS, imported) && !imported.mEvents.empty()
            ? imported.mEvents[0].mTick
            : -1;
    };

    CHECK(exportedTick() == OFF_GRID_TICK);
    auto settings = offGrid.mSequencer.getSettings();
    settings.mIsQuantizing = true;
    settings.mQuantizeTicks = BEAT;
    offGrid.mSequencer.setSettings(settings);
    CHECK(exportedTick() == BEAT);

    // A short note quantized onto the loop end wraps to the start with its note-off: it stays short
    constexpr int64_t LATE_TICK = BAR - BEAT / 8;
    constexpr int64_t LATE_LENGTH = BEAT / 16;
    Harness late;
    late.mSequencer.setEvents({event(LATE_TICK, noteOn(60)), event(LATE_TICK + LATE_LENGTH, noteOff(60))}, 0);
    late.mSequencer.setSettings(settings);
    auto lateExported = late.mSequencer.exportStandardMidiFile();
    CHECK(smf::read(lateExported.data(), static_cast<int32_t>(lateExported.size()), MAX_SEQUENCER_SLOTS, imported));
    CHECK(imported.mEvents.size() == 2);

    if (imported.mEvents.size() == 2) {
        CHECK(imported.mEvents[0].mTick == 0 && isNoteOn(imported.mEvents[0].mPacket.mWords[0]));
        CHECK(imported.mEvents[1].mTick == LATE_LENGTH);
    }
}

} // namespace

int main()
{
    testArmedAutoLengthTake();
    testTiming();
    testOverdubAndMetronome();
    testRecordsNotesOnly();
    testStopRecordingWhileArmed();
    testChangingTheLength();
    testNoStuckNotes();
    testReleaseSlotNotes();
    testUndo();
    testPhrases();
    testUndoingEveryTakeWhileRecordingInAuto();
    testStandardMidiFile();
    testStartPlaybackFromTick();
    testSeek();
    testReplaceEventsWhilePlaying();
    testReplaceEventsIsRefusedWhileRecording();
    testTracksPlayTheirOwnEvents();
    testRecordsEveryTrack();
    testNoteProbabilityIsKeptInThePacket();
    testANoteAlwaysPlaysAtOneHundredPercentAndNeverAtZero();
    testTheChanceOfANoteFollowsItsPercentage();
    testTheSeedMakesTheVariationReproducible();

    if (gFailures > 0) {
        std::printf("%d failure(s)\n", gFailures);
        return EXIT_FAILURE;
    }

    std::printf("All passed\n");
    return EXIT_SUCCESS;
}
