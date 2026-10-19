// Desktop tests of the rack: slot processors in the chain, bypass, level and mix, the NaN guard,
// processor lifecycle and MIDI routing. Fake processors stand in for plugins. See CMakeLists.txt.

#include "RackEngine.h"
#include "slot/SlotEvents.h"
#include <cmath>
#include <cstdio>
#include <array>
#include <cstdlib>
#include <limits>
#include <vector>

using namespace greenhouse;

namespace
{

constexpr int32_t TRACK_COUNT = 1;
constexpr int32_t TWO_TRACKS = 2;
constexpr int32_t SECOND_TRACK = 1;
constexpr float QUARTER_GAIN = 0.25f;
constexpr int32_t EFFECT_SLOT_INDEX = 1;
constexpr int32_t BLOCK_FRAMES = 64;
// Not a multiple of BLOCK_FRAMES: blocks are rendered independently of the callback size
constexpr int32_t CALLBACK_FRAMES = 100;
constexpr int32_t OTHER_SAMPLE_RATE = 44100;
constexpr int32_t EVENT_CAPACITY_WORDS = 256;
constexpr float SOURCE_LEVEL = 0.25f;
constexpr float EFFECT_GAIN = 2.0f;
constexpr float HALF_GAIN = 0.5f;
constexpr float TOLERANCE = 1e-6f;
constexpr uint32_t MIDI2_NOTE_ON = 0x40900000u;
constexpr uint32_t MIDI2_NOTE_OFF = 0x40800000u;
constexpr uint32_t STATUS_MASK = 0xF0F00000u;
constexpr uint32_t FULL_VELOCITY = 0xFFFF0000u;
constexpr uint32_t TEST_NOTE = 60;
constexpr int32_t NOTE_LENGTH_TICKS = SEQUENCER_PPQ;
constexpr int32_t LOOP_BARS = 1;

int gFailures = 0;

void check(bool condition, const char* description, int line)
{
    if (!condition) {
        std::printf("FAIL line %d: %s\n", line, description);
        gFailures++;
    }
}

#define CHECK(condition) check((condition), #condition, __LINE__)

/**
 * What a FakeProcessor went through, kept by the test since the rack owns and destroys the processor.
 * Declare it before the Rack, which reports to it until it is destroyed.
 */
struct Trace
{
    int32_t mPreparedSampleRate{0};
    int32_t mActivations{0};
    int32_t mDeactivations{0};
    int32_t mProcessCalls{0};
    int32_t mLastFrames{0};
    int32_t mQueuedBytes{0};
    int32_t mNoteOns{0};
    int32_t mNoteOffs{0};
    bool mIsDestroyed{false};
};

/**
 * A processor running in the engine, as embedded DSP would: it is prepared at the stream's rate.
 * A source (no audio input) outputs SOURCE_LEVEL; an effect multiplies its input by mGain.
 */
class FakeProcessor final : public SlotProcessor
{
public:
    FakeProcessor(Trace& trace, int32_t inputCount, int32_t outputCount, float gain = UNITY_GAIN)
        : mTrace(trace), mInputCount(inputCount), mOutputCount(outputCount), mGain(gain)
    {
    }

    ~FakeProcessor() override
    {
        mTrace.mIsDestroyed = true;
    }

    void prepareToPlay(int32_t sampleRate, int32_t maxBlockFrames) override
    {
        mTrace.mPreparedSampleRate = sampleRate;
    }

    void activate() override
    {
        mIsActive = true;
        mTrace.mActivations++;
    }

    void deactivate() override
    {
        mIsActive = false;
        mTrace.mDeactivations++;
    }

    int32_t getAudioInputCount() const override
    {
        return mInputCount;
    }

    int32_t getAudioOutputCount() const override
    {
        return mOutputCount;
    }

    int32_t getEventCapacityWords() const override
    {
        return EVENT_CAPACITY_WORDS;
    }

    bool queueEvents(const uint8_t* data, int32_t size) override
    {
        mTrace.mQueuedBytes += size;
        return true;
    }

    bool isActive() const override
    {
        return mIsActive;
    }

    bool canProcess(int32_t frames, int32_t sampleRate) const override
    {
        return mIsActive && sampleRate == mTrace.mPreparedSampleRate && frames <= MAX_DSP_BLOCK_FRAMES;
    }

    bool process(const float* input, int32_t frames, const uint32_t* events, int32_t eventWordCount, SlotOutput& out) override
    {
        mTrace.mProcessCalls++;
        mTrace.mLastFrames = frames;
        countNotes(events, eventWordCount);

        for (int32_t i = 0; i < frames; i++) {
            auto left = mInputCount > 0 ? input[i * STEREO_CHANNEL_COUNT] * mGain : SOURCE_LEVEL;
            auto right = mInputCount > 0 ? input[i * STEREO_CHANNEL_COUNT + 1] * mGain : SOURCE_LEVEL;
            mLeft[i] = mOutputsNan ? std::numeric_limits<float>::quiet_NaN() : left;
            mRight[i] = mOutputsNan ? std::numeric_limits<float>::quiet_NaN() : right;
        }

        if (mOutputCount == 0) {
            return false;
        }

        out.mLeft = mLeft.data();
        out.mRight = mRight.data();
        return true;
    }

    void setOutputsNan(bool outputsNan)
    {
        mOutputsNan = outputsNan;
    }

private:
    void countNotes(const uint32_t* events, int32_t eventWordCount)
    {
        auto i = 0;

        while (i < eventWordCount) {
            auto status = events[i] & STATUS_MASK;

            if (status == MIDI2_NOTE_ON) {
                mTrace.mNoteOns++;
            } else if (status == MIDI2_NOTE_OFF) {
                mTrace.mNoteOffs++;
            }

            i += ump::getWordCount(events[i]);
        }
    }

    Trace& mTrace;
    int32_t mInputCount;
    int32_t mOutputCount;
    float mGain;
    bool mIsActive{false};
    bool mOutputsNan{false};
    std::array<float, MAX_DSP_BLOCK_FRAMES> mLeft{};
    std::array<float, MAX_DSP_BLOCK_FRAMES> mRight{};
};

/** A configured rack that renders whole callbacks of CALLBACK_FRAMES. */
class Rack
{
public:
    explicit Rack(int32_t trackCount = TRACK_COUNT)
    {
        mEngine.configure(BLOCK_FRAMES, trackCount);
    }

    ~Rack()
    {
        mEngine.shutdown();
    }

    FakeProcessor* setSlot(int32_t slotIndex, Trace& trace, int32_t inputCount, int32_t outputCount, float gain = UNITY_GAIN)
    {
        auto processor = std::make_unique<FakeProcessor>(trace, inputCount, outputCount, gain);
        auto raw = processor.get();
        mEngine.setSlotProcessor(slotIndex, std::move(processor));
        return raw;
    }

    /** Renders one callback and returns its output. */
    const std::vector<float>& render()
    {
        mEngine.renderCallback(mOutput.data(), CALLBACK_FRAMES);
        return mOutput;
    }

    /**
     * Renders until the output only holds blocks rendered from now on: the first callback can still
     * play the end of a block rendered before a change.
     */
    void renderSettled()
    {
        render();
        render();
    }

    /**
     * Renders until a ramp that started at the next block is over, and its tail is out of the callback: a block
     * is not a multiple of the callback, so the ramp's end can fall in the next callback too.
     */
    void renderRamped()
    {
        renderSettled();
        render();
    }

    /** Whether every sample of the last callback is value. */
    bool lastOutputIs(float value) const
    {
        for (auto sample : mOutput) {
            if (std::fabs(sample - value) > TOLERANCE) {
                return false;
            }
        }

        return true;
    }

    RackEngine mEngine;

private:
    std::vector<float> mOutput = std::vector<float>(CALLBACK_FRAMES * STEREO_CHANNEL_COUNT);
};

void testChain()
{
    Trace instrument;
    Trace effect;
    Rack rack;
    rack.setSlot(INSTRUMENT_SLOT_INDEX, instrument, 0, STEREO_CHANNEL_COUNT);
    rack.setSlot(EFFECT_SLOT_INDEX, effect, STEREO_CHANNEL_COUNT, STEREO_CHANNEL_COUNT, EFFECT_GAIN);
    rack.mEngine.startStreaming();
    rack.render();

    CHECK(rack.lastOutputIs(SOURCE_LEVEL * EFFECT_GAIN));
    // 100 frames take two blocks of 64
    CHECK(instrument.mProcessCalls == 2);
    CHECK(instrument.mLastFrames == BLOCK_FRAMES);
    CHECK(effect.mProcessCalls == 2);
}

void testEmptyRackIsSilent()
{
    Rack rack;
    rack.mEngine.startStreaming();
    rack.render();

    CHECK(rack.lastOutputIs(0.0f));
}

void testBypass()
{
    Trace instrument;
    Trace effect;
    Rack rack;
    rack.setSlot(INSTRUMENT_SLOT_INDEX, instrument, 0, STEREO_CHANNEL_COUNT);
    rack.setSlot(EFFECT_SLOT_INDEX, effect, STEREO_CHANNEL_COUNT, STEREO_CHANNEL_COUNT, EFFECT_GAIN);
    rack.mEngine.startStreaming();
    rack.mEngine.setSlotBypassed(EFFECT_SLOT_INDEX, true);
    rack.render();

    // The first bypassed block still runs the processor, so it can end its notes, but its output is not used
    CHECK(rack.lastOutputIs(SOURCE_LEVEL));
    CHECK(effect.mProcessCalls == 1);

    rack.mEngine.setSlotBypassed(EFFECT_SLOT_INDEX, false);
    rack.renderSettled();

    CHECK(rack.lastOutputIs(SOURCE_LEVEL * EFFECT_GAIN));
}

void testEffectWithoutAudioIsSkipped()
{
    Trace instrument;
    Trace effect;
    Rack rack;
    rack.setSlot(INSTRUMENT_SLOT_INDEX, instrument, 0, STEREO_CHANNEL_COUNT);
    rack.setSlot(EFFECT_SLOT_INDEX, effect, 0, STEREO_CHANNEL_COUNT);
    rack.mEngine.startStreaming();
    rack.render();

    CHECK(rack.lastOutputIs(SOURCE_LEVEL));
    CHECK(effect.mProcessCalls == 0);
}

void testInvalidOutputIsDropped()
{
    Trace instrument;
    Trace effect;
    Rack rack;
    rack.setSlot(INSTRUMENT_SLOT_INDEX, instrument, 0, STEREO_CHANNEL_COUNT);
    auto processor = rack.setSlot(EFFECT_SLOT_INDEX, effect, STEREO_CHANNEL_COUNT, STEREO_CHANNEL_COUNT, EFFECT_GAIN);
    processor->setOutputsNan(true);
    rack.mEngine.startStreaming();
    rack.render();

    CHECK(rack.lastOutputIs(SOURCE_LEVEL));
    CHECK(rack.mEngine.getSlotInvalidBlocks(EFFECT_SLOT_INDEX) == 2);
}

void testLevelAndMix()
{
    Trace instrument;
    Trace effect;
    Rack rack;
    rack.setSlot(INSTRUMENT_SLOT_INDEX, instrument, 0, STEREO_CHANNEL_COUNT);
    rack.setSlot(EFFECT_SLOT_INDEX, effect, STEREO_CHANNEL_COUNT, STEREO_CHANNEL_COUNT, EFFECT_GAIN);
    rack.mEngine.startStreaming();

    // Changes ramp over a block, then hold
    rack.mEngine.setSlotMix(EFFECT_SLOT_INDEX, DRY_ONLY_MIX);
    rack.renderSettled();
    CHECK(rack.lastOutputIs(SOURCE_LEVEL));

    rack.mEngine.setSlotMix(EFFECT_SLOT_INDEX, WET_ONLY_MIX);
    rack.mEngine.setSlotGain(EFFECT_SLOT_INDEX, HALF_GAIN);
    rack.renderSettled();
    CHECK(rack.lastOutputIs(SOURCE_LEVEL * EFFECT_GAIN * HALF_GAIN));
}

void testLifecycle()
{
    Trace first;
    Trace second;
    Trace third;
    Rack rack;
    rack.setSlot(INSTRUMENT_SLOT_INDEX, first, 0, STEREO_CHANNEL_COUNT);

    // configure() opened the stream: the processor is prepared at its rate, and runs once it starts
    CHECK(first.mPreparedSampleRate == DEFAULT_SAMPLE_RATE);
    CHECK(first.mActivations == 0);

    rack.mEngine.startStreaming();
    CHECK(first.mActivations == 1);

    rack.mEngine.stopStreaming();
    CHECK(first.mDeactivations == 1);

    // Reopened on a device running at another rate
    rack.mEngine.closeStream();
    rack.mEngine.setDeviceSampleRate(OTHER_SAMPLE_RATE);
    rack.mEngine.startStreaming();
    CHECK(first.mPreparedSampleRate == OTHER_SAMPLE_RATE);
    CHECK(first.mActivations == 2);
    rack.render();
    CHECK(rack.lastOutputIs(SOURCE_LEVEL));

    // Added to a running rack: prepared and activated before it plays; the one it replaces is destroyed
    rack.setSlot(INSTRUMENT_SLOT_INDEX, second, 0, STEREO_CHANNEL_COUNT);
    CHECK(first.mIsDestroyed);
    CHECK(second.mPreparedSampleRate == OTHER_SAMPLE_RATE);
    CHECK(second.mActivations == 1);

    rack.mEngine.setSlotProcessor(INSTRUMENT_SLOT_INDEX, nullptr);
    CHECK(second.mIsDestroyed);
    rack.renderSettled();
    CHECK(rack.lastOutputIs(0.0f));

    rack.setSlot(INSTRUMENT_SLOT_INDEX, third, 0, STEREO_CHANNEL_COUNT);
    rack.mEngine.shutdown();
    CHECK(third.mIsDestroyed);
}

void testLiveEvents()
{
    Trace instrument;
    Rack rack;
    rack.setSlot(INSTRUMENT_SLOT_INDEX, instrument, 0, STEREO_CHANNEL_COUNT);
    uint32_t noteOn[] = {MIDI2_NOTE_ON | (TEST_NOTE << ump::NOTE_SHIFT), FULL_VELOCITY};
    rack.mEngine.sendUmpToSlot(INSTRUMENT_SLOT_INDEX, reinterpret_cast<const uint8_t*>(noteOn), sizeof(noteOn));

    CHECK(instrument.mQueuedBytes == static_cast<int32_t>(sizeof(noteOn)));

    // An empty slot takes nothing
    rack.mEngine.sendUmpToSlot(EFFECT_SLOT_INDEX, reinterpret_cast<const uint8_t*>(noteOn), sizeof(noteOn));
}

void testSequencerEvents()
{
    Trace instrument;
    Rack rack;
    rack.setSlot(INSTRUMENT_SLOT_INDEX, instrument, 0, STEREO_CHANNEL_COUNT);

    ump::Packet noteOn;
    noteOn.mWords[0] = MIDI2_NOTE_ON | (TEST_NOTE << ump::NOTE_SHIFT);
    noteOn.mWords[1] = FULL_VELOCITY;
    noteOn.mWordCount = ump::getWordCount(noteOn.mWords[0]);
    auto noteOff = noteOn;
    noteOff.mWords[0] = MIDI2_NOTE_OFF | (TEST_NOTE << ump::NOTE_SHIFT);

    auto& sequencer = rack.mEngine.getSequencer();
    sequencer.setEvents({SequenceEvent{0, INSTRUMENT_SLOT_INDEX, 0, 0, noteOn},
                         SequenceEvent{NOTE_LENGTH_TICKS, INSTRUMENT_SLOT_INDEX, 0, 0, noteOff}}, LOOP_BARS);
    rack.mEngine.startStreaming();
    sequencer.startPlayback();
    rack.render();

    CHECK(instrument.mNoteOns == 1);
    CHECK(instrument.mNoteOffs == 0);

    // Bypassing ends the note in the block that starts the bypass
    rack.mEngine.setSlotBypassed(INSTRUMENT_SLOT_INDEX, true);
    rack.render();

    CHECK(instrument.mNoteOffs == 1);
}

void testFourEffectsInSerial()
{
    Trace instrument;
    std::array<Trace, EFFECT_SLOTS_PER_TRACK> effects;
    Rack rack;
    rack.setSlot(INSTRUMENT_SLOT_INDEX, instrument, 0, STEREO_CHANNEL_COUNT);

    for (int32_t i = 0; i < EFFECT_SLOTS_PER_TRACK; i++) {
        rack.setSlot(INSTRUMENT_SLOT_INDEX + 1 + i, effects[i], STEREO_CHANNEL_COUNT, STEREO_CHANNEL_COUNT, EFFECT_GAIN);
    }

    rack.mEngine.startStreaming();
    rack.render();

    CHECK(rack.lastOutputIs(SOURCE_LEVEL * std::pow(EFFECT_GAIN, static_cast<float>(EFFECT_SLOTS_PER_TRACK))));

    for (const auto& effect : effects) {
        CHECK(effect.mProcessCalls == 2);
    }
}

void testTracksAreSummed()
{
    Trace firstInstrument;
    Trace firstEffect;
    Trace secondInstrument;
    Rack rack(TWO_TRACKS);
    rack.setSlot(getInstrumentSlotOfTrack(0), firstInstrument, 0, STEREO_CHANNEL_COUNT);
    rack.setSlot(getInstrumentSlotOfTrack(0) + 1, firstEffect, STEREO_CHANNEL_COUNT, STEREO_CHANNEL_COUNT, EFFECT_GAIN);
    rack.setSlot(getInstrumentSlotOfTrack(SECOND_TRACK), secondInstrument, 0, STEREO_CHANNEL_COUNT);
    rack.mEngine.startStreaming();
    rack.render();

    // The first track's effect only processes its own track: the second one joins at the master
    CHECK(rack.lastOutputIs(SOURCE_LEVEL * EFFECT_GAIN + SOURCE_LEVEL));
    CHECK(firstEffect.mProcessCalls == 2);
    CHECK(secondInstrument.mProcessCalls == 2);
}

void testTrackGainMuteAndSolo()
{
    Trace first;
    Trace second;
    Rack rack(TWO_TRACKS);
    rack.setSlot(getInstrumentSlotOfTrack(0), first, 0, STEREO_CHANNEL_COUNT);
    rack.setSlot(getInstrumentSlotOfTrack(SECOND_TRACK), second, 0, STEREO_CHANNEL_COUNT);
    rack.mEngine.startStreaming();
    rack.renderRamped();
    CHECK(rack.lastOutputIs(SOURCE_LEVEL * 2));

    // Changes ramp over a block, then hold
    rack.mEngine.setTrackGain(SECOND_TRACK, HALF_GAIN);
    rack.renderRamped();
    CHECK(rack.lastOutputIs(SOURCE_LEVEL + SOURCE_LEVEL * HALF_GAIN));

    rack.mEngine.setTrackMuted(0, true);
    rack.renderRamped();
    CHECK(rack.lastOutputIs(SOURCE_LEVEL * HALF_GAIN));

    // A muted track still runs: its plugins keep their state
    auto callsWhileMuted = first.mProcessCalls;
    rack.render();
    CHECK(first.mProcessCalls > callsWhileMuted);

    rack.mEngine.setTrackMuted(0, false);
    rack.mEngine.setTrackSoloed(0, true);
    rack.renderRamped();
    CHECK(rack.lastOutputIs(SOURCE_LEVEL));

    // Several tracks can be soloed; a muted one stays silent even soloed
    rack.mEngine.setTrackSoloed(SECOND_TRACK, true);
    rack.renderRamped();
    CHECK(rack.lastOutputIs(SOURCE_LEVEL + SOURCE_LEVEL * HALF_GAIN));

    rack.mEngine.setTrackMuted(0, true);
    rack.renderRamped();
    CHECK(rack.lastOutputIs(SOURCE_LEVEL * HALF_GAIN));

    // Nothing soloed any more: every track not muted is heard again
    rack.mEngine.setTrackMuted(0, false);
    rack.mEngine.setTrackSoloed(0, false);
    rack.mEngine.setTrackSoloed(SECOND_TRACK, false);
    rack.renderRamped();
    CHECK(rack.lastOutputIs(SOURCE_LEVEL + SOURCE_LEVEL * HALF_GAIN));
}

void testTrackLevels()
{
    Trace first;
    Trace second;
    Rack rack(TWO_TRACKS);
    rack.setSlot(getInstrumentSlotOfTrack(0), first, 0, STEREO_CHANNEL_COUNT);
    rack.setSlot(getInstrumentSlotOfTrack(SECOND_TRACK), second, 0, STEREO_CHANNEL_COUNT);
    rack.mEngine.setTrackGain(SECOND_TRACK, HALF_GAIN);
    rack.mEngine.startStreaming();
    rack.renderRamped();

    std::array<float, TWO_TRACKS * STEREO_CHANNEL_COUNT> levels{};
    std::array<float, STEREO_CHANNEL_COUNT> master{};
    rack.mEngine.getTrackLevels(levels.data(), TWO_TRACKS);
    rack.mEngine.getMasterLevels(master.data());

    // The tracks' levels are in the mix: after their gain
    CHECK(std::fabs(levels[0] - SOURCE_LEVEL) < TOLERANCE && std::fabs(levels[1] - SOURCE_LEVEL) < TOLERANCE);
    CHECK(std::fabs(levels[2] - SOURCE_LEVEL * HALF_GAIN) < TOLERANCE);
    CHECK(std::fabs(master[0] - (SOURCE_LEVEL + SOURCE_LEVEL * HALF_GAIN)) < TOLERANCE);

    rack.mEngine.setTrackMuted(SECOND_TRACK, true);

    for (auto i = 0; i < 40; i++) {
        rack.render();
    }

    // A muted track's meter falls to nothing
    rack.mEngine.getTrackLevels(levels.data(), TWO_TRACKS);
    CHECK(levels[2] == 0.0f && levels[0] > 0.0f);
}

void testEmptyTracksAndLimits()
{
    Trace instrument;
    Rack rack(TWO_TRACKS);
    CHECK(rack.mEngine.getNumTracks() == TWO_TRACKS);

    // Only the second track makes sound: the first one is empty
    rack.setSlot(getInstrumentSlotOfTrack(SECOND_TRACK), instrument, 0, STEREO_CHANNEL_COUNT);
    rack.mEngine.startStreaming();
    rack.render();
    CHECK(rack.lastOutputIs(SOURCE_LEVEL));

    // Slots and tracks past the rack are ignored
    Trace stray;
    rack.setSlot(TWO_TRACKS * SLOTS_PER_TRACK, stray, 0, STEREO_CHANNEL_COUNT);
    rack.mEngine.setTrackMuted(TWO_TRACKS, true);
    rack.mEngine.setTrackGain(-1, 0.0f);
    rack.render();
    CHECK(stray.mProcessCalls == 0);
    CHECK(rack.lastOutputIs(SOURCE_LEVEL));

    Rack large(MAX_TRACKS + 1);
    CHECK(large.mEngine.getNumTracks() == MAX_TRACKS);
}

void testSequencerPlaysEachTrackInstrument()
{
    Trace firstInstrument;
    Trace firstEffect;
    Trace secondInstrument;
    Rack rack(TWO_TRACKS);
    rack.setSlot(getInstrumentSlotOfTrack(0), firstInstrument, 0, STEREO_CHANNEL_COUNT);
    rack.setSlot(getInstrumentSlotOfTrack(0) + 1, firstEffect, STEREO_CHANNEL_COUNT, STEREO_CHANNEL_COUNT);
    rack.setSlot(getInstrumentSlotOfTrack(SECOND_TRACK), secondInstrument, 0, STEREO_CHANNEL_COUNT);

    ump::Packet noteOn;
    noteOn.mWords[0] = MIDI2_NOTE_ON | (TEST_NOTE << ump::NOTE_SHIFT);
    noteOn.mWords[1] = FULL_VELOCITY;
    noteOn.mWordCount = ump::getWordCount(noteOn.mWords[0]);
    auto noteOff = noteOn;
    noteOff.mWords[0] = MIDI2_NOTE_OFF | (TEST_NOTE << ump::NOTE_SHIFT);

    // Track 2 plays two notes, track 1 one
    auto& sequencer = rack.mEngine.getSequencer();
    sequencer.setEvents({SequenceEvent{0, 0, 0, 0, noteOn}, SequenceEvent{NOTE_LENGTH_TICKS, 0, 0, 0, noteOff},
                         SequenceEvent{0, SECOND_TRACK, 0, 0, noteOn}, SequenceEvent{NOTE_LENGTH_TICKS / 2, SECOND_TRACK, 0, 0, noteOff},
                         SequenceEvent{NOTE_LENGTH_TICKS, SECOND_TRACK, 0, 0, noteOn}}, LOOP_BARS);
    rack.mEngine.startStreaming();
    sequencer.startPlayback();
    rack.render();

    CHECK(firstInstrument.mNoteOns == 1);
    CHECK(secondInstrument.mNoteOns == 1);
    // The effects of a track do not get the sequencer's notes
    CHECK(firstEffect.mNoteOns == 0 && firstEffect.mNoteOffs == 0);

    // Bypassing a track's instrument ends only its notes
    rack.mEngine.setSlotBypassed(getInstrumentSlotOfTrack(SECOND_TRACK), true);
    rack.render();
    CHECK(secondInstrument.mNoteOffs == 1 && firstInstrument.mNoteOffs == 0);
}

void testRecordsLiveNotesUnderTheirTrack()
{
    Trace firstInstrument;
    Trace firstEffect;
    Trace secondInstrument;
    Rack rack(TWO_TRACKS);
    rack.setSlot(getInstrumentSlotOfTrack(0), firstInstrument, 0, STEREO_CHANNEL_COUNT);
    rack.setSlot(getInstrumentSlotOfTrack(0) + 1, firstEffect, STEREO_CHANNEL_COUNT, STEREO_CHANNEL_COUNT);
    rack.setSlot(getInstrumentSlotOfTrack(SECOND_TRACK), secondInstrument, 0, STEREO_CHANNEL_COUNT);
    rack.mEngine.startStreaming();

    auto& sequencer = rack.mEngine.getSequencer();
    sequencer.startRecording();
    rack.render();

    uint32_t noteOn[] = {MIDI2_NOTE_ON | (TEST_NOTE << ump::NOTE_SHIFT), FULL_VELOCITY};
    auto bytes = reinterpret_cast<const uint8_t*>(noteOn);

    // Not an instrument: nothing to record
    rack.mEngine.sendUmpToSlot(getInstrumentSlotOfTrack(0) + 1, bytes, sizeof(noteOn));
    CHECK(sequencer.getEvents().empty());

    rack.mEngine.sendUmpToSlot(getInstrumentSlotOfTrack(SECOND_TRACK), bytes, sizeof(noteOn));
    auto events = sequencer.getEvents();
    CHECK(events.size() == 1 && events[0].mSlot == SECOND_TRACK);
    CHECK(secondInstrument.mQueuedBytes == static_cast<int32_t>(sizeof(noteOn)));
}

void testSlotEvents()
{
    // What the Kotlin host sends for a parameter (UmpHelper.aapUmpSysex8ParameterPlain()): ID 42 at half its range
    constexpr uint32_t PARAMETER_ID = 42;
    constexpr uint32_t HALF_TRANSPORT_VALUE = 0x80000000u;
    constexpr double MIN_VALUE = 20.0;
    constexpr double MAX_VALUE = 220.0;
    constexpr double HALF_PLAIN_VALUE = 120.0;
    constexpr double VALUE_TOLERANCE = 1e-6;
    // SysEx8 in group 0 with the universal ID, then 0x7F on channel 0
    constexpr uint32_t PARAMETER_WORD0 = 0x5000007Eu;
    constexpr uint32_t PARAMETER_WORD1 = 0x7F000000u;
    const uint32_t parameterWords[] = {PARAMETER_WORD0, PARAMETER_WORD1, PARAMETER_ID, HALF_TRANSPORT_VALUE};
    ParameterChange change;

    CHECK(ump::getWordCount(parameterWords[0]) == PARAMETER_SYSEX8_WORD_COUNT);
    CHECK(readParameterChange(parameterWords, PARAMETER_SYSEX8_WORD_COUNT, change));
    CHECK(change.mParameterId == PARAMETER_ID);
    CHECK(std::fabs(toPlainValue(change.mNormalizedValue, MIN_VALUE, MAX_VALUE) - HALF_PLAIN_VALUE) < VALUE_TOLERANCE);

    const uint32_t noteOnWords[] = {MIDI2_NOTE_ON | (TEST_NOTE << ump::NOTE_SHIFT), FULL_VELOCITY};
    CHECK(!readParameterChange(noteOnWords, ump::getWordCount(noteOnWords[0]), change));
    CHECK(!isJrTimestamp(noteOnWords[0]));

    // As the sequencer writes them, 100 frames into a block. JR ticks are exact to about 1.5 frames at 48 kHz.
    constexpr int32_t FRAME_OFFSET = 100;
    constexpr int32_t FRAME_TOLERANCE = 2;
    auto ticks = static_cast<uint32_t>(FRAME_OFFSET * ump::JR_TIMESTAMP_TICKS_PER_SECOND / DEFAULT_SAMPLE_RATE);
    auto timestamp = ump::makeJrTimestamp(ticks);

    CHECK(isJrTimestamp(timestamp));
    CHECK(getJrTimestampTicks(timestamp) == ticks);
    CHECK(std::abs(jrTicksToFrames(ticks, DEFAULT_SAMPLE_RATE) - FRAME_OFFSET) <= FRAME_TOLERANCE);
}

} // namespace

int main()
{
    testChain();
    testEmptyRackIsSilent();
    testBypass();
    testEffectWithoutAudioIsSkipped();
    testInvalidOutputIsDropped();
    testLevelAndMix();
    testLifecycle();
    testLiveEvents();
    testSequencerEvents();
    testSlotEvents();
    testFourEffectsInSerial();
    testTracksAreSummed();
    testTrackGainMuteAndSolo();
    testTrackLevels();
    testEmptyTracksAndLimits();
    testSequencerPlaysEachTrackInstrument();
    testRecordsLiveNotesUnderTheirTrack();

    if (gFailures > 0) {
        std::printf("%d failure(s)\n", gFailures);
        return EXIT_FAILURE;
    }

    std::printf("All passed\n");
    return EXIT_SUCCESS;
}
