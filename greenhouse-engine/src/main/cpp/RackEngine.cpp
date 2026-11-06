#include "RackEngine.h"
#include "utils/AudioSimd.h"
#include "utils/Logging.h"
#include <algorithm>
#include <cmath>
#include <cstring>

namespace greenhouse
{

namespace
{

using Clock = std::chrono::steady_clock;

double smoothLoad(double previous, Clock::duration elapsed, double blockDurationNs)
{
    auto elapsedNs = static_cast<double>(std::chrono::duration_cast<std::chrono::nanoseconds>(elapsed).count());
    return previous * DSP_LOAD_EMA_PREVIOUS_WEIGHT + (elapsedNs / blockDurationNs) * DSP_LOAD_EMA_CURRENT_WEIGHT;
}

float nextPeak(float previous, float measured)
{
    auto next = measured >= previous ? measured : previous * METER_DECAY_FACTOR;
    return next < METER_MIN_THRESHOLD ? 0.0f : next;
}

/**
 * The gain of each channel of a stereo signal panned to [pan] (-1 left, 1 right). Equal power: the channel away
 * from the pan fades out along a quarter of a cosine and the other one stays at unity, so the centre leaves the
 * signal as it is and the far side is silent.
 */
void getPanGains(float pan, float& left, float& right)
{
    constexpr float HALF_PI = 1.57079632679f;
    auto clamped = std::clamp(pan, MIN_PAN, MAX_PAN);

    // The cosine of a half pi is not exactly zero in floats: the far side must be silent, and exactly
    left = clamped > CENTER_PAN ? std::max(SILENT_GAIN, std::cos(clamped * HALF_PI)) : UNITY_GAIN;
    right = clamped < CENTER_PAN ? std::max(SILENT_GAIN, std::cos(-clamped * HALF_PI)) : UNITY_GAIN;

    if (clamped >= MAX_PAN) {
        left = SILENT_GAIN;
    }

    if (clamped <= MIN_PAN) {
        right = SILENT_GAIN;
    }
}

} // namespace

RackEngine::~RackEngine()
{
    closeStream();
}

// ---------------------------------------------------------------------------------------------
// Control thread
// ---------------------------------------------------------------------------------------------

void RackEngine::configure(int32_t framesPerCallback, int32_t numTracks)
{
    closeStream();

    {
        std::lock_guard<std::mutex> lock(mControlMutex);
        clearSlotsLocked();

        if (numTracks > MAX_TRACKS) {
            LOGW("%d tracks requested, the rack supports %d", numTracks, MAX_TRACKS);
        }

        auto clampedTracks = std::clamp(numTracks, 1, MAX_TRACKS);
        mNumTracks.store(clampedTracks);
        mNumSlots.store(clampedTracks * SLOTS_PER_TRACK);
        // The engine lives as long as the process: a new configuration starts from an empty sequence
        mSequencer.reset();
        mFramesPerCallback.store(std::clamp(framesPerCallback, MIN_DSP_BLOCK_FRAMES, MAX_DSP_BLOCK_FRAMES));
    }

    openStream();
    LOGI("Rack engine configured: sampleRate=%d, framesPerCallback=%d, numTracks=%d, numSlots=%d",
         getSampleRate(), mFramesPerCallback.load(), mNumTracks.load(), mNumSlots.load());
}

void RackEngine::shutdown()
{
    closeStream();

    std::lock_guard<std::mutex> lock(mControlMutex);
    clearSlotsLocked();
}

void RackEngine::clearSlotsLocked()
{
    // Only called with the stream closed, so no quiescence wait is needed
    for (auto& slot : mSlots) {
        slot.mProcessor.store(nullptr);
        slot.mOwnedProcessor.reset();
        slot.mIsBypassed.store(false, std::memory_order_relaxed);
        slot.mGain.store(UNITY_GAIN, std::memory_order_relaxed);
        slot.mMix.store(WET_ONLY_MIX, std::memory_order_relaxed);
        slot.mCurrentGain = UNITY_GAIN;
        slot.mCurrentMix = WET_ONLY_MIX;
        slot.mWasBypassed = false;
        slot.mCpuLoad.store(0.0f, std::memory_order_relaxed);
        slot.mPeakL.store(0.0f, std::memory_order_relaxed);
        slot.mPeakR.store(0.0f, std::memory_order_relaxed);
        slot.mInvalidBlocks.store(0, std::memory_order_relaxed);
    }

    for (auto& track : mTracks) {
        track.mGain.store(UNITY_GAIN, std::memory_order_relaxed);
        track.mPan.store(CENTER_PAN, std::memory_order_relaxed);
        track.mIsMuted.store(false, std::memory_order_relaxed);
        track.mIsSoloed.store(false, std::memory_order_relaxed);
        track.mCurrentGainL = UNITY_GAIN;
        track.mCurrentGainR = UNITY_GAIN;
        track.mPeakL.store(0.0f, std::memory_order_relaxed);
        track.mPeakR.store(0.0f, std::memory_order_relaxed);
    }

    mIsAnyTrackSoloed.store(false, std::memory_order_relaxed);
    mMasterPeakL.store(0.0f, std::memory_order_relaxed);
    mMasterPeakR.store(0.0f, std::memory_order_relaxed);
}

void RackEngine::setFramesPerCallback(int32_t framesPerCallback)
{
    std::lock_guard<std::mutex> lock(mControlMutex);

    // The audio thread picks the new size up at its next block boundary
    mFramesPerCallback.store(std::clamp(framesPerCallback, MIN_DSP_BLOCK_FRAMES, MAX_DSP_BLOCK_FRAMES), std::memory_order_relaxed);
    updateMinimumBufferSizeLocked();
}

void RackEngine::updateMinimumBufferSizeLocked()
{
    // A callback that has to render a whole block needs room for that block on top of what is queued
    auto blockFrames = mFramesPerCallback.load(std::memory_order_relaxed);
    setMinimumBufferSize(std::max(getFramesPerBurst(), blockFrames) * BUFFER_SIZE_SAFETY_FACTOR);
}

void RackEngine::setSlotProcessor(int32_t slotIndex, std::unique_ptr<SlotProcessor> processor)
{
    std::lock_guard<std::mutex> lock(mControlMutex);

    if (!isValidSlot(slotIndex)) {
        return;
    }

    auto& slot = mSlots[slotIndex];

    // Unpublish the old processor and wait for the audio thread to let go of it before destroying it.
    // An AAP plugin instance is destroyed by the host as soon as we return.
    slot.mProcessor.store(nullptr);
    waitForAudioThreadQuiescence();
    slot.mOwnedProcessor = std::move(processor);
    slot.mIsBypassed.store(false, std::memory_order_relaxed);
    slot.mInvalidBlocks.store(0, std::memory_order_relaxed);

    auto newProcessor = slot.mOwnedProcessor.get();

    // Only the instrument of a track plays the sequencer's events, which are filed under the track
    if (newProcessor == nullptr) {
        if (isInstrumentSlot(slotIndex)) {
            mSequencer.setSlotEventCapacity(getTrackOfSlot(slotIndex), MAX_SLOT_EVENT_WORDS);
        }

        LOGI("Slot %d cleared", slotIndex);
        return;
    }

    if (isInstrumentSlot(slotIndex)) {
        mSequencer.setSlotEventCapacity(getTrackOfSlot(slotIndex), newProcessor->getEventCapacityWords());
    }

    // Before the first open, prepareToPlay() prepares it with the rest of the rack
    if (getSampleRate() > 0) {
        newProcessor->prepareToPlay(getSampleRate(), MAX_DSP_BLOCK_FRAMES);
    }

    if (isStreaming()) {
        newProcessor->activate();
    }

    slot.mProcessor.store(newProcessor);
    LOGI("Slot %d processor set: audioIn=%d, audioOut=%d", slotIndex,
         newProcessor->getAudioInputCount(), newProcessor->getAudioOutputCount());
}

void RackEngine::setSlotBypassed(int32_t slotIndex, bool bypassed)
{
    if (isValidSlot(slotIndex)) {
        mSlots[slotIndex].mIsBypassed.store(bypassed, std::memory_order_relaxed);
    }
}

void RackEngine::setSlotGain(int32_t slotIndex, float gain)
{
    if (isValidSlot(slotIndex)) {
        mSlots[slotIndex].mGain.store(std::clamp(gain, SILENT_GAIN, MAX_SLOT_GAIN), std::memory_order_relaxed);
    }
}

void RackEngine::setSlotMix(int32_t slotIndex, float mix)
{
    if (isValidSlot(slotIndex)) {
        mSlots[slotIndex].mMix.store(std::clamp(mix, DRY_ONLY_MIX, WET_ONLY_MIX), std::memory_order_relaxed);
    }
}

void RackEngine::setTrackGain(int32_t trackIndex, float gain)
{
    if (isValidTrack(trackIndex)) {
        mTracks[trackIndex].mGain.store(std::clamp(gain, SILENT_GAIN, MAX_TRACK_GAIN), std::memory_order_relaxed);
    }
}

void RackEngine::setTrackPan(int32_t trackIndex, float pan)
{
    if (isValidTrack(trackIndex)) {
        mTracks[trackIndex].mPan.store(std::clamp(pan, MIN_PAN, MAX_PAN), std::memory_order_relaxed);
    }
}

void RackEngine::setTrackMuted(int32_t trackIndex, bool muted)
{
    if (isValidTrack(trackIndex)) {
        mTracks[trackIndex].mIsMuted.store(muted, std::memory_order_relaxed);
    }
}

void RackEngine::setTrackSoloed(int32_t trackIndex, bool soloed)
{
    if (!isValidTrack(trackIndex)) {
        return;
    }

    // Held so two solo changes cannot leave the any-solo flag out of date
    std::lock_guard<std::mutex> lock(mControlMutex);
    mTracks[trackIndex].mIsSoloed.store(soloed, std::memory_order_relaxed);

    auto isAnySoloed = false;

    for (int32_t i = 0; i < mNumTracks.load(std::memory_order_relaxed); i++) {
        isAnySoloed = isAnySoloed || mTracks[i].mIsSoloed.load(std::memory_order_relaxed);
    }

    mIsAnyTrackSoloed.store(isAnySoloed, std::memory_order_relaxed);
}

void RackEngine::sendUmpToSlot(int32_t slotIndex, const uint8_t* data, int32_t size)
{
    if (!isValidSlot(slotIndex) || data == nullptr || size <= 0) {
        return;
    }

    // Held so the processor cannot be swapped out and destroyed while events are queued on it
    std::lock_guard<std::mutex> lock(mControlMutex);
    auto processor = mSlots[slotIndex].mProcessor.load();

    if (processor == nullptr || !processor->queueEvents(data, size)) {
        return;
    }

    // Recorded where the processor receives it: at the start of the next block. Only notes played on an
    // instrument are recorded, under the instrument's track.
    if (isInstrumentSlot(slotIndex)) {
        mSequencer.recordInput(getTrackOfSlot(slotIndex), data, size);
    }
}

float RackEngine::getTotalCpuLoad() const
{
    return mTotalCpuLoad.load(std::memory_order_relaxed);
}

float RackEngine::getSlotCpuLoad(int32_t slotIndex) const
{
    if (!isValidSlot(slotIndex)) {
        return 0.0f;
    }

    return mSlots[slotIndex].mCpuLoad.load(std::memory_order_relaxed);
}

int32_t RackEngine::getSlotInvalidBlocks(int32_t slotIndex) const
{
    if (!isValidSlot(slotIndex)) {
        return 0;
    }

    return mSlots[slotIndex].mInvalidBlocks.load(std::memory_order_relaxed);
}

void RackEngine::getAllSlotLevels(float* outLevels, int32_t maxSlots) const
{
    auto count = std::min(mNumSlots.load(std::memory_order_relaxed), maxSlots);

    for (int32_t i = 0; i < count; i++) {
        outLevels[i * STEREO_CHANNEL_COUNT] = mSlots[i].mPeakL.load(std::memory_order_relaxed);
        outLevels[i * STEREO_CHANNEL_COUNT + 1] = mSlots[i].mPeakR.load(std::memory_order_relaxed);
    }
}

void RackEngine::getTrackLevels(float* outLevels, int32_t maxTracks) const
{
    auto count = std::min(mNumTracks.load(std::memory_order_relaxed), maxTracks);

    for (int32_t i = 0; i < count; i++) {
        outLevels[i * STEREO_CHANNEL_COUNT] = mTracks[i].mPeakL.load(std::memory_order_relaxed);
        outLevels[i * STEREO_CHANNEL_COUNT + 1] = mTracks[i].mPeakR.load(std::memory_order_relaxed);
    }
}

void RackEngine::getMasterLevels(float* outLevels) const
{
    outLevels[0] = mMasterPeakL.load(std::memory_order_relaxed);
    outLevels[1] = mMasterPeakR.load(std::memory_order_relaxed);
}

bool RackEngine::isValidSlot(int32_t slotIndex) const
{
    return slotIndex >= 0 && slotIndex < mNumSlots.load(std::memory_order_relaxed);
}

bool RackEngine::isValidTrack(int32_t trackIndex) const
{
    return trackIndex >= 0 && trackIndex < mNumTracks.load(std::memory_order_relaxed);
}

// ---------------------------------------------------------------------------------------------
// OboeEngine hooks (control thread, mControlMutex held)
// ---------------------------------------------------------------------------------------------

void RackEngine::prepareToPlay(int32_t sampleRate, int32_t framesPerBurst)
{
    mRenderSampleRate = sampleRate;
    updateMinimumBufferSizeLocked();

    for (auto& slot : mSlots) {
        auto processor = slot.mProcessor.load();

        if (processor != nullptr) {
            processor->prepareToPlay(sampleRate, MAX_DSP_BLOCK_FRAMES);
        }
    }
}

void RackEngine::streamStarting()
{
    auto numSlots = mNumSlots.load();

    for (int32_t i = 0; i < numSlots; i++) {
        auto processor = mSlots[i].mProcessor.load();

        if (processor != nullptr) {
            processor->activate();
        }

        mSlots[i].mSmoothedLoad = 0.0;
    }

    // A track that was muted or soloed while the stream was stopped starts at its level, not ramping to it
    for (auto& track : mTracks) {
        float panGainL = UNITY_GAIN;
        float panGainR = UNITY_GAIN;
        getPanGains(track.mPan.load(std::memory_order_relaxed), panGainL, panGainR);
        track.mCurrentGainL = track.mGain.load(std::memory_order_relaxed) * panGainL;
        track.mCurrentGainR = track.mGain.load(std::memory_order_relaxed) * panGainR;
    }

    mBlockFrames = 0;
    mBlockReadFrame = 0;
    mSmoothedTotalLoad = 0.0;
}

void RackEngine::flushState()
{
    for (auto& slot : mSlots) {
        slot.mPeakL.store(0.0f, std::memory_order_relaxed);
        slot.mPeakR.store(0.0f, std::memory_order_relaxed);
        slot.mCpuLoad.store(0.0f, std::memory_order_relaxed);

        auto processor = slot.mProcessor.load();

        if (processor != nullptr) {
            processor->deactivate();
        }
    }

    for (auto& track : mTracks) {
        track.mPeakL.store(0.0f, std::memory_order_relaxed);
        track.mPeakR.store(0.0f, std::memory_order_relaxed);
    }

    mMasterPeakL.store(0.0f, std::memory_order_relaxed);
    mMasterPeakR.store(0.0f, std::memory_order_relaxed);
    mTotalCpuLoad.store(0.0f, std::memory_order_relaxed);
    mSequencer.onStreamStopped();
}

// ---------------------------------------------------------------------------------------------
// Audio thread
// ---------------------------------------------------------------------------------------------

void RackEngine::process(float* output, int32_t numFrames)
{
    // Copy out what is left of the current block and render a new one whenever it runs dry
    auto remaining = numFrames;

    while (remaining > 0) {
        if (mBlockReadFrame >= mBlockFrames) {
            renderBlock(mFramesPerCallback.load(std::memory_order_relaxed));
        }

        auto chunk = std::min(remaining, mBlockFrames - mBlockReadFrame);
        std::memcpy(output, mBlockBuffer.data() + mBlockReadFrame * STEREO_CHANNEL_COUNT,
                    static_cast<size_t>(chunk * STEREO_CHANNEL_COUNT) * sizeof(float));
        output += chunk * STEREO_CHANNEL_COUNT;
        mBlockReadFrame += chunk;
        remaining -= chunk;
    }
}

int32_t RackEngine::estimateWorkload() const
{
    auto workload = ADPF_BASE_WORKLOAD;
    auto numSlots = mNumSlots.load(std::memory_order_relaxed);

    for (int32_t i = 0; i < numSlots; i++) {
        auto processor = mSlots[i].mProcessor.load();

        if (processor != nullptr && !mSlots[i].mIsBypassed.load(std::memory_order_relaxed) && processor->isActive()) {
            workload += ADPF_WORKLOAD_PER_ACTIVE_SLOT;
        }
    }

    return workload;
}

void RackEngine::renderBlock(int32_t frames)
{
    auto blockDurationNs = static_cast<double>(frames) * NANOS_PER_SECOND / static_cast<double>(mRenderSampleRate);
    auto blockStart = Clock::now();
    auto numTracks = mNumTracks.load(std::memory_order_relaxed);
    auto isAnyTrackSoloed = mIsAnyTrackSoloed.load(std::memory_order_relaxed);

    mSequencer.beginBlock(frames, mRenderSampleRate);

    // The master starts from silence and every track adds its signal
    std::fill_n(mBlockBuffer.data(), frames * STEREO_CHANNEL_COUNT, 0.0f);

    for (int32_t i = 0; i < numTracks; i++) {
        renderTrack(i, frames, isAnyTrackSoloed);
    }

    // After the tracks, so effects do not process the clicks
    mSequencer.renderMetronome(mBlockBuffer.data(), frames);

    float masterPeakL = 0.0f;
    float masterPeakR = 0.0f;
    simd::measureStereoPeak(mBlockBuffer.data(), frames, masterPeakL, masterPeakR);
    mMasterPeakL.store(nextPeak(mMasterPeakL.load(std::memory_order_relaxed), masterPeakL), std::memory_order_relaxed);
    mMasterPeakR.store(nextPeak(mMasterPeakR.load(std::memory_order_relaxed), masterPeakR), std::memory_order_relaxed);

    mSmoothedTotalLoad = smoothLoad(mSmoothedTotalLoad, Clock::now() - blockStart, blockDurationNs);
    mTotalCpuLoad.store(static_cast<float>(mSmoothedTotalLoad), std::memory_order_relaxed);

    mBlockFrames = frames;
    mBlockReadFrame = 0;
}

/**
 * Runs the track's chain over mTrackBuffer, the instrument first and each effect over the previous
 * output, and adds the result to the master at the track's level. A muted or unsoloed track still
 * runs, so its plugins keep their state and the notes the sequencer plays on it end.
 */
void RackEngine::renderTrack(int32_t trackIndex, int32_t frames, bool isAnyTrackSoloed)
{
    auto& track = mTracks[trackIndex];
    auto blockDurationNs = static_cast<double>(frames) * NANOS_PER_SECOND / static_cast<double>(mRenderSampleRate);
    auto firstSlot = trackIndex * SLOTS_PER_TRACK;
    auto hasSignal = false;

    // The instrument starts from silence; each following slot processes the previous output in place
    std::fill_n(mTrackBuffer.data(), frames * STEREO_CHANNEL_COUNT, 0.0f);

    for (int32_t i = 0; i < SLOTS_PER_TRACK; i++) {
        auto slotIndex = firstSlot + i;
        auto& slot = mSlots[slotIndex];
        auto slotStart = Clock::now();
        auto rendered = renderSlot(slotIndex, frames);

        // Nothing was applied: jump to the host settings so they don't ramp from stale values later
        if (!rendered) {
            slot.mCurrentGain = slot.mGain.load(std::memory_order_relaxed);
            slot.mCurrentMix = slot.mMix.load(std::memory_order_relaxed);
        }

        slot.mSmoothedLoad = smoothLoad(slot.mSmoothedLoad, Clock::now() - slotStart, blockDurationNs);
        slot.mCpuLoad.store(static_cast<float>(slot.mSmoothedLoad), std::memory_order_relaxed);

        float peakL = 0.0f;
        float peakR = 0.0f;

        if (rendered) {
            simd::measureStereoPeak(mTrackBuffer.data(), frames, peakL, peakR);
            hasSignal = true;
        }

        slot.mPeakL.store(nextPeak(slot.mPeakL.load(std::memory_order_relaxed), peakL), std::memory_order_relaxed);
        slot.mPeakR.store(nextPeak(slot.mPeakR.load(std::memory_order_relaxed), peakR), std::memory_order_relaxed);
    }

    auto isAudible = !track.mIsMuted.load(std::memory_order_relaxed) &&
                     (!isAnyTrackSoloed || track.mIsSoloed.load(std::memory_order_relaxed));
    auto trackGain = isAudible ? track.mGain.load(std::memory_order_relaxed) : SILENT_GAIN;
    float panGainL = UNITY_GAIN;
    float panGainR = UNITY_GAIN;
    getPanGains(track.mPan.load(std::memory_order_relaxed), panGainL, panGainR);
    auto targetGainL = trackGain * panGainL;
    auto targetGainR = trackGain * panGainR;
    float peakL = 0.0f;
    float peakR = 0.0f;

    // An empty track, or one silent now and before, adds nothing
    auto isSilent = track.mCurrentGainL == SILENT_GAIN && track.mCurrentGainR == SILENT_GAIN &&
                    targetGainL == SILENT_GAIN && targetGainR == SILENT_GAIN;

    if (hasSignal && !isSilent) {
        simd::measureStereoPeak(mTrackBuffer.data(), frames, peakL, peakR);
        simd::addStereoWithChannelGainRamps(mTrackBuffer.data(), mBlockBuffer.data(), frames,
                                            track.mCurrentGainL, targetGainL, track.mCurrentGainR, targetGainR);
        // The peaks before the fader and the pan, scaled by their gains
        peakL *= targetGainL;
        peakR *= targetGainR;
    }

    track.mCurrentGainL = targetGainL;
    track.mCurrentGainR = targetGainR;
    track.mPeakL.store(nextPeak(track.mPeakL.load(std::memory_order_relaxed), peakL), std::memory_order_relaxed);
    track.mPeakR.store(nextPeak(track.mPeakR.load(std::memory_order_relaxed), peakR), std::memory_order_relaxed);
}

/**
 * Runs one slot over mTrackBuffer in place. Returns true if the processor output replaced the buffer;
 * false leaves it untouched (empty, bypassed or failed slots, and blocks with NaN or infinite
 * samples, pass the signal through).
 */
bool RackEngine::renderSlot(int32_t slotIndex, int32_t frames)
{
    auto& slot = mSlots[slotIndex];
    auto isInstrument = isInstrumentSlot(slotIndex);
    auto trackIndex = getTrackOfSlot(slotIndex);
    auto processor = slot.mProcessor.load();
    auto isBypassed = slot.mIsBypassed.load(std::memory_order_relaxed);

    if (!isBypassed) {
        slot.mWasBypassed = false;
    }

    // The first bypassed block the processor can run still runs it, to end the notes the sequencer plays on it
    auto isBypassStarting = isBypassed && !slot.mWasBypassed;

    if (processor == nullptr || (isBypassed && !isBypassStarting) || !processor->canProcess(frames, mRenderSampleRate)) {
        return false;
    }

    // An effect without audio in and out cannot sit in the chain; an instrument may be MIDI-only
    if (!isInstrument && (processor->getAudioInputCount() == 0 || processor->getAudioOutputCount() == 0)) {
        return false;
    }

    if (isBypassStarting) {
        if (isInstrument) {
            mSequencer.releaseSlotNotes(trackIndex);
        }

        slot.mWasBypassed = true;
    }

    // The sequencer plays the track's instrument; the effects only get live events
    int32_t eventWordCount = 0;
    const uint32_t* events = nullptr;

    if (isInstrument) {
        events = mSequencer.getSlotEvents(trackIndex, eventWordCount);
    }

    SlotOutput out;
    auto hasOutput = processor->process(mTrackBuffer.data(), frames, events, eventWordCount, out);

    // Once bypassed, its output is not used
    if (isBypassStarting || !hasOutput) {
        return false;
    }

    // A processor can output NaN or infinite samples (e.g. Odin2's first block after a session reload).
    // Passed on, they click, and a NaN can get stuck in the next slots' filters and delay lines for good.
    if (!simd::allFinite(out.mLeft, frames) || !simd::allFinite(out.mRight, frames)) {
        slot.mInvalidBlocks.fetch_add(1, std::memory_order_relaxed);
        return false;
    }

    // mTrackBuffer still holds the slot's input: the dry signal of the host mix
    auto targetGain = slot.mGain.load(std::memory_order_relaxed);
    auto targetMix = slot.mMix.load(std::memory_order_relaxed);
    auto isUnityWet = slot.mCurrentGain == UNITY_GAIN && targetGain == UNITY_GAIN &&
                      slot.mCurrentMix == WET_ONLY_MIX && targetMix == WET_ONLY_MIX;

    if (isUnityWet && out.mRight == out.mLeft) {
        simd::interleaveMonoToStereo(out.mLeft, mTrackBuffer.data(), frames);
    } else if (isUnityWet) {
        simd::interleaveStereo(out.mLeft, out.mRight, mTrackBuffer.data(), frames);
    } else {
        simd::mixStereo(out.mLeft, out.mRight, mTrackBuffer.data(), frames,
                        (WET_ONLY_MIX - slot.mCurrentMix) * slot.mCurrentGain, (WET_ONLY_MIX - targetMix) * targetGain,
                        slot.mCurrentMix * slot.mCurrentGain, targetMix * targetGain);
    }

    slot.mCurrentGain = targetGain;
    slot.mCurrentMix = targetMix;
    return true;
}

} // namespace greenhouse
