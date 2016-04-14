/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer.audio;

import com.google.android.exoplayer.C;
import com.google.android.exoplayer.util.Ac3Util;
import com.google.android.exoplayer.util.Assertions;
import com.google.android.exoplayer.util.MimeTypes;
import com.google.android.exoplayer.util.Util;
// AMZN_CHANGE_BEGIN
import com.google.android.exoplayer.util.Logger;
import com.google.android.exoplayer.util.AmazonQuirks;
import com.google.android.exoplayer.audio.DolbyPassthroughAudioTrack;
import com.google.android.exoplayer.util.MimeTypes;
import android.os.SystemClock;
// AMZN_CHANGE_END
import android.annotation.TargetApi;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTimestamp;
import android.media.MediaFormat;
import android.os.ConditionVariable;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;

/**
 * Plays audio data. The implementation delegates to an {@link android.media.AudioTrack} and handles
 * playback position smoothing, non-blocking writes and reconfiguration.
 *
 * <p>If {@link #isInitialized} returns {@code false}, the instance can be {@link #initialize}d.
 * After initialization, start playback by calling {@link #play}.
 *
 * <p>Call {@link #handleBuffer} to write data for playback.
 *
 * <p>Call {@link #handleDiscontinuity} when a buffer is skipped.
 *
 * <p>Call {@link #reconfigure} when the output format changes.
 *
 * <p>Call {@link #reset} to free resources. It is safe to re-{@link #initialize} the instance.
 *
 * <p>Call {@link #release} when the instance will no longer be used.
 */
@TargetApi(16)
public final class AudioTrack {

  /**
   * Thrown when a failure occurs instantiating an {@link android.media.AudioTrack}.
   */
  public static final class InitializationException extends Exception {

    /** The state as reported by {@link android.media.AudioTrack#getState()}. */
    public final int audioTrackState;

    public InitializationException(
        int audioTrackState, int sampleRate, int channelConfig, int bufferSize) {
      super("AudioTrack init failed: " + audioTrackState + ", Config(" + sampleRate + ", "
          + channelConfig + ", " + bufferSize + ")");
      this.audioTrackState = audioTrackState;
    }

  }

  /**
   * Thrown when a failure occurs writing to an {@link android.media.AudioTrack}.
   */
  public static final class WriteException extends Exception {

    /** The value returned from {@link android.media.AudioTrack#write(byte[], int, int)}. */
    public final int errorCode;

    public WriteException(int errorCode) {
      super("AudioTrack write failed: " + errorCode);
      this.errorCode = errorCode;
    }

  }

  /**
   * Thrown when {@link android.media.AudioTrack#getTimestamp} returns a spurious timestamp, if
   * {@code AudioTrack#failOnSpuriousAudioTimestamp} is set.
   */
  public static final class InvalidAudioTrackTimestampException extends RuntimeException {

    public InvalidAudioTrackTimestampException(String message) {
      super(message);
    }

  }

  /** Returned in the result of {@link #handleBuffer} if the buffer was discontinuous. */
  public static final int RESULT_POSITION_DISCONTINUITY = 1;
  /** Returned in the result of {@link #handleBuffer} if the buffer can be released. */
  public static final int RESULT_BUFFER_CONSUMED = 2;

  /** Represents an unset {@link android.media.AudioTrack} session identifier. */
  public static final int SESSION_ID_NOT_SET = 0;

  /** Returned by {@link #getCurrentPositionUs} when the position is not set. */
  public static final long CURRENT_POSITION_NOT_SET = Long.MIN_VALUE;

  /** A minimum length for the {@link android.media.AudioTrack} buffer, in microseconds. */
  private static final long MIN_BUFFER_DURATION_US = 250000;
  /** A maximum length for the {@link android.media.AudioTrack} buffer, in microseconds. */
  private static final long MAX_BUFFER_DURATION_US = 750000;
  /**
   * A multiplication factor to apply to the minimum buffer size requested by the underlying
   * {@link android.media.AudioTrack}.
   */
  private static final int BUFFER_MULTIPLICATION_FACTOR = 4;

  private static final String TAG = AudioTrack.class.getSimpleName();

  /**
   * AudioTrack timestamps are deemed spurious if they are offset from the system clock by more
   * than this amount.
   *
   * <p>This is a fail safe that should not be required on correctly functioning devices.
   */
  private static final long MAX_AUDIO_TIMESTAMP_OFFSET_US = 5 * C.MICROS_PER_SECOND;

  /**
   * AudioTrack latencies are deemed impossibly large if they are greater than this amount.
   *
   * <p>This is a fail safe that should not be required on correctly functioning devices.
   */
  private static final long MAX_LATENCY_US = 5 * C.MICROS_PER_SECOND;

  /**
   * Value for {@link #passthroughBitrate} before the bitrate has been calculated.
   */
  private static final int UNKNOWN_BITRATE = 0;

  private static final int START_NOT_SET = 0;
  private static final int START_IN_SYNC = 1;
  private static final int START_NEED_SYNC = 2;

  private static final int MAX_PLAYHEAD_OFFSET_COUNT = 10;
  private static final int MIN_PLAYHEAD_OFFSET_SAMPLE_INTERVAL_US = 30000;
  private static final int MIN_TIMESTAMP_SAMPLE_INTERVAL_US = 500000;

  /**
   * Whether to enable a workaround for an issue where an audio effect does not keep its session
   * active across releasing/initializing a new audio track, on platform API version < 21.
   * <p>
   * The flag must be set before creating a player.
   */
  public static boolean enablePreV21AudioSessionWorkaround = false;

  /**
   * Whether to throw an {@link InvalidAudioTrackTimestampException} when a spurious timestamp is
   * reported from {@link android.media.AudioTrack#getTimestamp}.
   * <p>
   * The flag must be set before creating a player. Should be set to {@code true} for testing and
   * debugging purposes only.
   */
  public static boolean failOnSpuriousAudioTimestamp = false;

  private final AudioCapabilities audioCapabilities;
  private final int streamType;
  private final ConditionVariable releasingConditionVariable;
  private final long[] playheadOffsets;
  private final AudioTrackUtil audioTrackUtil;

  /** Used to keep the audio session active on pre-V21 builds (see {@link #initialize()}). */
  private android.media.AudioTrack keepSessionIdAudioTrack;

  private android.media.AudioTrack audioTrack;
  private int sampleRate;
  private int channelConfig;
  private int encoding;
  private int frameSize;
  private int minBufferSize;
  private int bufferSize;
  private boolean isPassthrough;//AMZN_CHANGE_ONELINE
  private int nextPlayheadOffsetIndex;
  private int playheadOffsetCount;
  private long smoothedPlayheadOffsetUs;
  private long lastPlayheadSampleTimeUs;
  private boolean audioTimestampSet;
  private long lastTimestampSampleTimeUs;

  private Method getLatencyMethod;
  private Method getDirectTrackAudioFormat; // AMZN_CHANGE_ONELINE
  private long submittedBytes;
  private int startMediaTimeState;
  private long startMediaTimeUs;
  private long resumeSystemTimeUs;
  private long latencyUs;
  private float volume;

  private byte[] temporaryBuffer;
  private int temporaryBufferOffset;
  private int temporaryBufferSize;

  /**
   * Bitrate measured in kilobits per second, if using passthrough.
   */
  private int passthroughBitrate;

  private static final Logger log = new Logger(Logger.Module.Audio, TAG); // AMZN_CHANGE_ONELINE

  // AMZN_CHANGE_BEGIN
  /** A boolean to enable latency quirk.
  Enabled when getPlayHeadPosition includes audio latencies */
  private boolean isLatencyQuirkEnabled = false;
  private boolean isDolbyPassthroughQuirkEnabled = false;
  // AMZN_CHANGE_END

  /**
   * Creates an audio track with default audio capabilities (no encoded audio passthrough support).
   */
  public AudioTrack() {
    this(null, AudioManager.STREAM_MUSIC);
  }

  /**
   * Creates an audio track using the specified audio capabilities and stream type.
   *
   * @param audioCapabilities The current audio playback capabilities.
   * @param streamType The type of audio stream for the underlying {@link android.media.AudioTrack}.
   */
  public AudioTrack(AudioCapabilities audioCapabilities, int streamType) {
    this.audioCapabilities = audioCapabilities;
    this.streamType = streamType;
    // AMZN_CHANGE_BEGIN
    isLatencyQuirkEnabled = AmazonQuirks.isLatencyQuirkEnabled();
    log.i("isLatencyQuirkEnabled = " + isLatencyQuirkEnabled);
    isDolbyPassthroughQuirkEnabled = AmazonQuirks.isDolbyPassthroughQuirkEnabled();
    log.i("isDolbyPassthroughQuirkEnabled = " + isDolbyPassthroughQuirkEnabled);
    isPassthrough = false;
    // AMZN_CHANGE_END
    releasingConditionVariable = new ConditionVariable(true);
    if (Util.SDK_INT >= 18) {
      try {
        getLatencyMethod =
            android.media.AudioTrack.class.getMethod("getLatency", (Class<?>[]) null);
      } catch (NoSuchMethodException e) {
        // There's no guarantee this method exists. Do nothing.
        log.w("getLatency method not found");
      }
    }
    // AMZN_CHANGE_BEGIN
    if (Util.SDK_INT >= 17) {
      try {
        getDirectTrackAudioFormat =
          android.media.AudioTrack.class.getMethod("getDirectTrackAudioFormat",
                                                      String.class,
                                                      int.class);
      } catch (NoSuchMethodException e) {
        // There's no guarantee this method exists. Do nothing.
        log.w("getDirectTrackAudioFormat method not found");
      }
    }
    if (Util.SDK_INT >= 19) {
      audioTrackUtil = new AudioTrackUtilV19(isLatencyQuirkEnabled, getLatencyMethod);
    } else {
      audioTrackUtil = new AudioTrackUtil(isLatencyQuirkEnabled, getLatencyMethod);
    }
    // AMZN_CHANGE_END

    playheadOffsets = new long[MAX_PLAYHEAD_OFFSET_COUNT];
    volume = 1.0f;
    startMediaTimeState = START_NOT_SET;
  }

  /**
   * Returns whether it is possible to play back input audio in the specified format using encoded
   * audio passthrough.
   */
  public boolean isPassthroughSupported(String mimeType) {
    return audioCapabilities != null
        && audioCapabilities.supportsEncoding(getEncodingForMimeType(mimeType));
  }

  /**
   * Returns whether the audio track has been successfully initialized via {@link #initialize} and
   * not yet {@link #reset}.
   */
  public boolean isInitialized() {
    return audioTrack != null;
  }

  // AMZN_CHANGE_BEGIN
  // This API is called from MediaCodecAudioTrackRenderer to skip
  // calling hasPendingData  to detect if the playback has ended or not since these APIs
  // always return true and fake the buffering state of audio track.
  // there is no way for us to depend on the audio track states to decide
  // if the playback has ended or not.
  public boolean applyDolbyPassthroughQuirk() {
    return (isPassthrough() && isDolbyPassthroughQuirkEnabled);
  }
  // AMZN_CHANGE_END

  /**
   * Returns the playback position in the stream starting at zero, in microseconds, or
   * {@link #CURRENT_POSITION_NOT_SET} if it is not yet available.
   *
   * <p>If the device supports it, the method uses the playback timestamp from
   * {@link android.media.AudioTrack#getTimestamp}. Otherwise, it derives a smoothed position by
   * sampling the {@link android.media.AudioTrack}'s frame position.
   *
   * @param sourceEnded Specify {@code true} if no more input buffers will be provided.
   * @return The playback position relative to the start of playback, in microseconds.
   */
  public long getCurrentPositionUs(boolean sourceEnded) {
    if (!hasCurrentPositionUs()) {
      log.v("getCurrentPositionUs: CURRENT_POSITION_NOT_SET");
      return CURRENT_POSITION_NOT_SET;
    }
    // for dolby passthrough case, we don't need to sync sample
    // params because we don't depend on play head position for timestamp
    if (audioTrack.getPlayState() == android.media.AudioTrack.PLAYSTATE_PLAYING
        && !applyDolbyPassthroughQuirk()) { // AMZN_CHANGE_ONELINE
      maybeSampleSyncParams();
    }

    long systemClockUs = System.nanoTime() / 1000;
    long currentPositionUs;
    // AMZN_CHANGE_BEGIN
    // for dolby passthrough case ,we just depend on getTimeStamp API
    // for audio video synchronization.
    if (applyDolbyPassthroughQuirk()) {
      long audioTimeStamp = 0;
      audioTimestampSet = audioTrackUtil.updateTimestamp();
      if (audioTimestampSet) {
        audioTimeStamp = audioTrackUtil.getTimestampNanoTime() / 1000;
      }
      currentPositionUs = audioTimeStamp + startMediaTimeUs;
      log.v("audioTimeStamp = " + audioTimeStamp +
              " startMediaTimeUs = " + startMediaTimeUs +
              " currentPositionUs = " + currentPositionUs);
    } else if (audioTimestampSet) { // AMZN_CHANGE_END
      // How long ago in the past the audio timestamp is (negative if it's in the future).
      long presentationDiff = systemClockUs - (audioTrackUtil.getTimestampNanoTime() / 1000);
      long framesDiff = durationUsToFrames(presentationDiff);
      // The position of the frame that's currently being presented.
      long currentFramePosition = audioTrackUtil.getTimestampFramePosition() + framesDiff;
      currentPositionUs = framesToDurationUs(currentFramePosition) + startMediaTimeUs;
      log.v("systemClockUs = " + systemClockUs +
            " presentationDiff = " + presentationDiff +
            " framesDiff = " + framesDiff +
            " currentFramePosition = " + currentFramePosition +
            " startMediaTimeUs = " + startMediaTimeUs +
            " currentPositionUs = "+ currentPositionUs);
    } else {
      if (playheadOffsetCount == 0) {
        // The AudioTrack has started, but we don't have any samples to compute a smoothed position.
        long playbackHeadPositionUs = audioTrackUtil.getPlaybackHeadPositionUs();
        currentPositionUs = playbackHeadPositionUs + startMediaTimeUs;
        log.v("playbackHeadPositionUs = " + playbackHeadPositionUs +
              " startMediaTimeUs = " + startMediaTimeUs +
              " currentPositionUs = " + currentPositionUs);
      } else {
        // getPlayheadPositionUs() only has a granularity of ~20ms, so we base the position off the
        // system clock (and a smoothed offset between it and the playhead position) so as to
        // prevent jitter in the reported positions.
        currentPositionUs = systemClockUs + smoothedPlayheadOffsetUs + startMediaTimeUs;
        log.v("startMediaTimeUs = " + startMediaTimeUs +
            " smoothedPlayheadOffsetUs = " + smoothedPlayheadOffsetUs +
            " systemClockUs = " + systemClockUs +
            " currentPositionUs = " + currentPositionUs);
      }
      if (!sourceEnded) {
        currentPositionUs -= latencyUs;
      }
    }
    log.d("currentPositionUs = " + currentPositionUs);
    return currentPositionUs;
  }

  /**
   * Initializes the audio track for writing new buffers using {@link #handleBuffer}.
   *
   * @return The audio track session identifier.
   */
  public int initialize() throws InitializationException {
    return initialize(SESSION_ID_NOT_SET);
  }

  /**
   * Initializes the audio track for writing new buffers using {@link #handleBuffer}.
   *
   * @param sessionId Audio track session identifier to re-use, or {@link #SESSION_ID_NOT_SET} to
   *     create a new one.
   * @return The new (or re-used) session identifier.
   */
  public int initialize(int sessionId) throws InitializationException {
    // If we're asynchronously releasing a previous audio track then we block until it has been
    // released. This guarantees that we cannot end up in a state where we have multiple audio
    // track instances. Without this guarantee it would be possible, in extreme cases, to exhaust
    // the shared memory that's available for audio track buffers. This would in turn cause the
    // initialization of the audio track to fail.
    releasingConditionVariable.block();
    log.i("initialize: session id = " + sessionId);
    // AMZN_CHANGE_BEGIN
    if (sessionId == SESSION_ID_NOT_SET) {
      if (applyDolbyPassthroughQuirk()) {
        audioTrack = new DolbyPassthroughAudioTrack(streamType, sampleRate, channelConfig, encoding,
            bufferSize, android.media.AudioTrack.MODE_STREAM);
      } else {
        audioTrack = new android.media.AudioTrack(streamType, sampleRate, channelConfig, encoding,
            bufferSize, android.media.AudioTrack.MODE_STREAM);
      }
    } else {
      // Re-attach to the same audio session.
      if (applyDolbyPassthroughQuirk()) {
        audioTrack = new DolbyPassthroughAudioTrack(streamType, sampleRate, channelConfig, encoding,
            bufferSize, android.media.AudioTrack.MODE_STREAM, sessionId);
      } else {
        audioTrack = new android.media.AudioTrack(streamType, sampleRate, channelConfig, encoding,
            bufferSize, android.media.AudioTrack.MODE_STREAM, sessionId);
      }
    }
    // AMZN_CHANGE_END
    checkAudioTrackInitialized();

    sessionId = audioTrack.getAudioSessionId();
    if (enablePreV21AudioSessionWorkaround) {
      if (Util.SDK_INT < 21) {
        // The workaround creates an audio track with a two byte buffer on the same session, and
        // does not release it until this object is released, which keeps the session active.
        if (keepSessionIdAudioTrack != null
            && sessionId != keepSessionIdAudioTrack.getAudioSessionId()) {
          releaseKeepSessionIdAudioTrack();
        }
        if (keepSessionIdAudioTrack == null) {
          int sampleRate = 4000; // Equal to private android.media.AudioTrack.MIN_SAMPLE_RATE.
          int channelConfig = AudioFormat.CHANNEL_OUT_MONO;
          int encoding = AudioFormat.ENCODING_PCM_16BIT;
          int bufferSize = 2; // Use a two byte buffer, as it is not actually used for playback.
          keepSessionIdAudioTrack = new android.media.AudioTrack(streamType, sampleRate,
              channelConfig, encoding, bufferSize, android.media.AudioTrack.MODE_STATIC, sessionId);
        }
      }
    }

    audioTrackUtil.reconfigure(audioTrack, needsPassthroughWorkarounds());
    setAudioTrackVolume();

    return sessionId;
  }

  /**
   * Reconfigures the audio track to play back media in {@code format}, inferring a buffer size from
   * the format.
   *
   * @param format Specifies the channel count and sample rate to play back.
   * @param passthrough Whether to play back using a passthrough encoding.
   */
  public void reconfigure(MediaFormat format, boolean passthrough) {
    reconfigure(format, passthrough, 0);
  }

  /**
   * Reconfigures the audio track to play back media in {@code format}.
   *
   * @param format Specifies the channel count and sample rate to play back.
   * @param passthrough Whether to playback using a passthrough encoding.
   * @param specifiedBufferSize A specific size for the playback buffer in bytes, or 0 to use a
   *     size inferred from the format.
   */
  public void reconfigure(MediaFormat format, boolean passthrough, int specifiedBufferSize) {
    log.i("reconfigure: format = " + format +
            " passthrough = " + passthrough +
            " specifiedBufferSize = " + specifiedBufferSize);
    int channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
    int channelConfig;
    switch (channelCount) {
      case 1:
        channelConfig = AudioFormat.CHANNEL_OUT_MONO;
        break;
      case 2:
        channelConfig = AudioFormat.CHANNEL_OUT_STEREO;
        break;
      case 6:
        channelConfig = AudioFormat.CHANNEL_OUT_5POINT1;
        break;
      case 8:
        channelConfig = AudioFormat.CHANNEL_OUT_7POINT1;
        break;
      default:
        throw new IllegalArgumentException("Unsupported channel count: " + channelCount);
    }
    int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
    String mimeType = format.getString(MediaFormat.KEY_MIME);
    // cache this boolean passthrough here for later use in isPassthrough()
    isPassthrough = passthrough;//AMZN_CHANGE_ONELINE
    int encoding = passthrough ? getEncodingForMimeType(mimeType) : AudioFormat.ENCODING_PCM_16BIT;
    log.i("mimeType = " + mimeType + " encoding = " + encoding);

    if (isInitialized() && this.sampleRate == sampleRate && this.channelConfig == channelConfig
        && this.encoding == encoding) {
      // We already have an audio track with the correct sample rate, encoding and channel config.
      return;
    }

    reset();

    this.encoding = encoding;
    this.sampleRate = sampleRate;
    this.channelConfig = channelConfig;
    passthroughBitrate = UNKNOWN_BITRATE;
    frameSize = 2 * channelCount; // 2 bytes per 16 bit sample * number of channels.
    minBufferSize = android.media.AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding);
    Assertions.checkState(minBufferSize != android.media.AudioTrack.ERROR_BAD_VALUE);

    if (specifiedBufferSize != 0) {
      bufferSize = specifiedBufferSize;
    } else {
      int multipliedBufferSize = minBufferSize * BUFFER_MULTIPLICATION_FACTOR;
      int minAppBufferSize = (int) durationUsToFrames(MIN_BUFFER_DURATION_US) * frameSize;
      int maxAppBufferSize = (int) Math.max(minBufferSize,
          durationUsToFrames(MAX_BUFFER_DURATION_US) * frameSize);
      bufferSize = multipliedBufferSize < minAppBufferSize ? minAppBufferSize
          : multipliedBufferSize > maxAppBufferSize ? maxAppBufferSize
          : multipliedBufferSize;
    }
    log.i("bufferSize = " + bufferSize + "minBufferSize = " + minBufferSize +
        " frameSize = " + frameSize + " sampleRate = " + sampleRate +
        " channelConfig = " + channelConfig);
  }

  /** Starts/resumes playing audio if the audio track has been initialized. */
  public void play() {
    log.i("calling play");
    if (isInitialized()) {
      resumeSystemTimeUs = System.nanoTime() / 1000;
      audioTrackUtil.play(); // AMZN_CHANGE_ONELINE
      audioTrack.play();
    }
  }

  /** Signals to the audio track that the next buffer is discontinuous with the previous buffer. */
  public void handleDiscontinuity() {
    // Force resynchronization after a skipped buffer.
    if (startMediaTimeState == START_IN_SYNC) {
      startMediaTimeState = START_NEED_SYNC;
    }
  }

  /**
   * Attempts to write {@code size} bytes from {@code buffer} at {@code offset} to the audio track.
   * Returns a bit field containing {@link #RESULT_BUFFER_CONSUMED} if the buffer can be released
   * (due to having been written), and {@link #RESULT_POSITION_DISCONTINUITY} if the buffer was
   * discontinuous with previously written data.
   *
   * @param buffer The buffer containing audio data to play back.
   * @param offset The offset in the buffer from which to consume data.
   * @param size The number of bytes to consume from {@code buffer}.
   * @param presentationTimeUs Presentation timestamp of the next buffer in microseconds.
   * @return A bit field with {@link #RESULT_BUFFER_CONSUMED} if the buffer can be released, and
   *     {@link #RESULT_POSITION_DISCONTINUITY} if the buffer was not contiguous with previously
   *     written data.
   * @throws WriteException If an error occurs writing the audio data.
   */
  public int handleBuffer(ByteBuffer buffer, int offset, int size, long presentationTimeUs)
      throws WriteException {
    log.d("handleBuffer : offset = " + offset + " size = " + size +
            " presentationTimeUs = " + presentationTimeUs);
    if (size == 0) {
      return RESULT_BUFFER_CONSUMED;
    }
    // AMZN_CHANGE_BEGIN
    //Skip workarounds for AC-3 passthrough AudioTrack issues if dolby passthrough quirk is enabled
    if (!applyDolbyPassthroughQuirk()) {
      if (needsPassthroughWorkarounds()) {
        // An AC-3 audio track continues to play data written while it is paused. Stop writing so its
        // buffer empties. See [Internal: b/18899620].
        if (audioTrack.getPlayState() == android.media.AudioTrack.PLAYSTATE_PAUSED) {
          return 0;
        }

        // A new AC-3 audio track's playback position continues to increase from the old track's
        // position for a short time after is has been released. Avoid writing data until the playback
        // head position actually returns to zero.
        if (audioTrack.getPlayState() == android.media.AudioTrack.PLAYSTATE_STOPPED
           && audioTrackUtil.getPlaybackHeadPosition() != 0) {
          return 0;
        }
      }
    }
    // AMZN_CHANGE_END
    int result = 0;
    if (temporaryBufferSize == 0) {
      if (passthroughBitrate == UNKNOWN_BITRATE) {
        if (isAc3Passthrough()) {
          passthroughBitrate = Ac3Util.getBitrate(size, sampleRate);
        } else if (isDtsPassthrough()) {
          int unscaledBitrate = size * 8 * sampleRate;
          int divisor = 1000 * 512;
          passthroughBitrate = (unscaledBitrate + divisor / 2) / divisor;
        }
      }
      // AMZN_CHANGE_BEGIN
      // for dolby passthrough quirk case we don't want to validate start times
      // because its not possible to validate it based on submitted bytes
      if (applyDolbyPassthroughQuirk()) {
        if (startMediaTimeState == START_NOT_SET) {
          startMediaTimeUs = presentationTimeUs;
          log.i("Setting StartMediaTimeUs = " + startMediaTimeUs);
          startMediaTimeState = START_IN_SYNC;
        }
      } else { // AMZN_CHANGE_END
        // This is the first time we've seen this {@code buffer}.
        // Note: presentationTimeUs corresponds to the end of the sample, not the start.
        long bufferStartTime = presentationTimeUs - framesToDurationUs(bytesToFrames(size));
        if (startMediaTimeState == START_NOT_SET) {
          startMediaTimeUs = Math.max(0, bufferStartTime);
          log.i("Setting StartMediaTimeUs = " + startMediaTimeUs);
          startMediaTimeState = START_IN_SYNC;
        } else {
          // Sanity check that bufferStartTime is consistent with the expected value.
          long expectedBufferStartTime = startMediaTimeUs
              + framesToDurationUs(bytesToFrames(submittedBytes));
          if (startMediaTimeState == START_IN_SYNC
              && Math.abs(expectedBufferStartTime - bufferStartTime) > 200000) {
            log.w("Discontinuity detected [expected " + expectedBufferStartTime + ", got "
                + bufferStartTime + "]");
            startMediaTimeState = START_NEED_SYNC;
          }
          if (startMediaTimeState == START_NEED_SYNC) {
            // Adjust startMediaTimeUs to be consistent with the current buffer's start time and the
            // number of bytes submitted.
            startMediaTimeUs += (bufferStartTime - expectedBufferStartTime);
            log.i("StartMediaTimeUs recalculated as = " + startMediaTimeUs);
            startMediaTimeState = START_IN_SYNC;
            result |= RESULT_POSITION_DISCONTINUITY;
          }
        }
      }
    }

    if (temporaryBufferSize == 0) {
      temporaryBufferSize = size;
      buffer.position(offset);
      // we need to copy data to temp buffer in case of dolby passthrough also
      // irrespective of SDK version.
      if (Util.SDK_INT < 21 || applyDolbyPassthroughQuirk()) { // AMZN_CHANGE_ONELINE
        // Copy {@code buffer} into {@code temporaryBuffer}.
        if (temporaryBuffer == null || temporaryBuffer.length < size) {
          temporaryBuffer = new byte[size];
        }
        buffer.get(temporaryBuffer, 0, size);
        temporaryBufferOffset = 0;
      }
    }

    int bytesWritten = 0;
    // AMZN_CHANGE_BEGIN
    // for dolby passthrough case, just write into the DolbyPassthroughAudioTrack
    // since its implementation is different than standard pcm audio track.
    // The DolbyPassthroughAudioTrack takes care of writing only in play state
    // and also writes into the track asynchronously. Also, we
    // cannot depend on playback head position to decide how much more data to write.
    if (applyDolbyPassthroughQuirk()) {
      // if there are no free buffers in AudioTrack, the write returns 0, indicating
      // it did not consume the buffer.
      bytesWritten = audioTrack.write(temporaryBuffer, temporaryBufferOffset, size);
      log.v("Writing to track returned: bytesToWrite = " + size +
              " bytes Written = " + bytesWritten);
    } else if (Util.SDK_INT < 21) { // AMZN_CHANGE_END
      // Work out how many bytes we can write without the risk of blocking.
      int bytesPending =
          (int) (submittedBytes - (audioTrackUtil.getPlaybackHeadPosition() * frameSize));
      int bytesToWrite = bufferSize - bytesPending;
      if (bytesToWrite > 0) {
        bytesToWrite = Math.min(temporaryBufferSize, bytesToWrite);
        bytesWritten = audioTrack.write(temporaryBuffer, temporaryBufferOffset, bytesToWrite);
        log.v("Writing to track returned: bytesToWrite = " + bytesToWrite +
              " bytes Written = " + bytesWritten);
        if (bytesWritten >= 0) {
          temporaryBufferOffset += bytesWritten;
        }
      }
    } else {
      bytesWritten = writeNonBlockingV21(audioTrack, buffer, temporaryBufferSize);
      log.v("writeNonBlockingV21:  temporaryBufferSize = " + temporaryBufferSize + " bytesWritten = " + bytesWritten);
    }

    if (bytesWritten < 0) {
      throw new WriteException(bytesWritten);
    }

    temporaryBufferSize -= bytesWritten;
    submittedBytes += bytesWritten;
    if (temporaryBufferSize == 0) {
      result |= RESULT_BUFFER_CONSUMED;
    }
    return result;
  }

  /**
   * Ensures that the last data passed to {@link #handleBuffer(ByteBuffer, int, int, long)} is
   * played out in full.
   */
  public void handleEndOfStream() {
    if (isInitialized()) {
      // AMZN_CHANGE_BEGIN
      if (applyDolbyPassthroughQuirk()) {
        log.i("calling stop");
        audioTrack.stop();
      } else {
        audioTrackUtil.handleEndOfStream(bytesToFrames(submittedBytes));
      }
      // AMZN_CHANGE_END
    }
  }

  @TargetApi(21)
  private static int writeNonBlockingV21(
      android.media.AudioTrack audioTrack, ByteBuffer buffer, int size) {
    return audioTrack.write(buffer, size, android.media.AudioTrack.WRITE_NON_BLOCKING);
  }

  /** Returns whether the audio track has more data pending that will be played back. */
  public boolean hasPendingData() {
    // AMZN_CHANGE_BEGIN
    if (!isInitialized()) {
        return false;
    }
    // for dolby passthrough case we always consider that audio track has
    // buffers to render even though we may not. We don't support buffering
    // at audio track level for dolby passthrough case.
    boolean isDataPending = applyDolbyPassthroughQuirk() ||
                     (bytesToFrames(submittedBytes) > audioTrackUtil.getPlaybackHeadPosition()
                     || overrideHasPendingData());
    log.v("hasPendingData = " + isDataPending);
    return isDataPending;
    // AMZN_CHANGE_END
  }

  /** Sets the playback volume. */
  public void setVolume(float volume) {
    if (this.volume != volume) {
      log.i("setVolume: volume = " + volume);
      this.volume = volume;
      setAudioTrackVolume();
    }
  }

  private void setAudioTrackVolume() {
    if (!isInitialized()) {
      // Do nothing.
    } else if (Util.SDK_INT >= 21) {
      setAudioTrackVolumeV21(audioTrack, volume);
    } else {
      setAudioTrackVolumeV3(audioTrack, volume);
    }
  }

  @TargetApi(21)
  private static void setAudioTrackVolumeV21(android.media.AudioTrack audioTrack, float volume) {
    audioTrack.setVolume(volume);
  }

  @SuppressWarnings("deprecation")
  private static void setAudioTrackVolumeV3(android.media.AudioTrack audioTrack, float volume) {
    audioTrack.setStereoVolume(volume, volume);
  }

  /** Pauses playback. */
  public void pause() {
    log.i("calling pause");
    if (isInitialized()) {
      resetSyncParams();
      audioTrackUtil.pause();
    }
  }

  /**
   * Releases the underlying audio track asynchronously. Calling {@link #initialize} will block
   * until the audio track has been released, so it is safe to initialize immediately after
   * resetting. The audio session may remain active until the instance is {@link #release}d.
   */
  public void reset() {
    log.i("calling reset");
    if (isInitialized()) {
      submittedBytes = 0;
      temporaryBufferSize = 0;
      startMediaTimeState = START_NOT_SET;
      latencyUs = 0;
      resetSyncParams();
      int playState = audioTrack.getPlayState();
      if (playState == android.media.AudioTrack.PLAYSTATE_PLAYING) {
        audioTrack.pause();
      }
      // AudioTrack.release can take some time, so we call it on a background thread.
      final android.media.AudioTrack toRelease = audioTrack;
      audioTrack = null;
      audioTrackUtil.reconfigure(null, false);
      releasingConditionVariable.close();
      new Thread() {
        @Override
        public void run() {
          try {
            toRelease.flush();
            toRelease.release();
          } finally {
            releasingConditionVariable.open();
          }
        }
      }.start();
    }
  }

  /** Releases all resources associated with this instance. */
  public void release() {
    reset();
    releaseKeepSessionIdAudioTrack();
  }

  /** Releases {@link #keepSessionIdAudioTrack} asynchronously, if it is non-{@code null}. */
  private void releaseKeepSessionIdAudioTrack() {
    if (keepSessionIdAudioTrack == null) {
      return;
    }

    // AudioTrack.release can take some time, so we call it on a background thread.
    final android.media.AudioTrack toRelease = keepSessionIdAudioTrack;
    keepSessionIdAudioTrack = null;
    new Thread() {
      @Override
      public void run() {
        toRelease.release();
      }
    }.start();
  }

  /** Returns whether {@link #getCurrentPositionUs} can return the current playback position. */
  private boolean hasCurrentPositionUs() {
    return isInitialized() && startMediaTimeState != START_NOT_SET;
  }

  /**
   * Updates the audio track latency and playback position parameters.
   */
  private void maybeSampleSyncParams() {
    long playbackPositionUs = audioTrackUtil.getPlaybackHeadPositionUs();
    if (playbackPositionUs == 0) {
      // The AudioTrack hasn't output anything yet.
      return;
    }
    log.v("playbackPositionUs = " + playbackPositionUs);
    long systemClockUs = System.nanoTime() / 1000;
    if (systemClockUs - lastPlayheadSampleTimeUs >= MIN_PLAYHEAD_OFFSET_SAMPLE_INTERVAL_US) {
      // Take a new sample and update the smoothed offset between the system clock and the playhead.
      playheadOffsets[nextPlayheadOffsetIndex] = playbackPositionUs - systemClockUs;
      nextPlayheadOffsetIndex = (nextPlayheadOffsetIndex + 1) % MAX_PLAYHEAD_OFFSET_COUNT;
      if (playheadOffsetCount < MAX_PLAYHEAD_OFFSET_COUNT) {
        playheadOffsetCount++;
      }
      lastPlayheadSampleTimeUs = systemClockUs;
      smoothedPlayheadOffsetUs = 0;
      for (int i = 0; i < playheadOffsetCount; i++) {
        smoothedPlayheadOffsetUs += playheadOffsets[i] / playheadOffsetCount;
      }
    }

    if (needsPassthroughWorkarounds()) {
      // Don't sample the timestamp and latency if this is an AC-3 passthrough AudioTrack on
      // platform API versions 21/22, as incorrect values are returned. See [Internal: b/21145353].
      return;
    }

    if (systemClockUs - lastTimestampSampleTimeUs >= MIN_TIMESTAMP_SAMPLE_INTERVAL_US) {
      audioTimestampSet = audioTrackUtil.updateTimestamp();
      if (audioTimestampSet) {
        // Perform sanity checks on the timestamp.
        long audioTimestampUs = audioTrackUtil.getTimestampNanoTime() / 1000;
        long audioTimestampFramePosition = audioTrackUtil.getTimestampFramePosition();
        if (audioTimestampUs < resumeSystemTimeUs) {
          // The timestamp corresponds to a time before the track was most recently resumed.
          audioTimestampSet = false;
          log.w( "The timestamp corresponds to a time before the track was most recently resumed: "
              + audioTimestampUs + ", " + resumeSystemTimeUs);
        } else if (Math.abs(audioTimestampUs - systemClockUs) > MAX_AUDIO_TIMESTAMP_OFFSET_US) {
          // The timestamp time base is probably wrong.
          String message = "Spurious audio timestamp (system clock mismatch): "
              + audioTimestampFramePosition + ", " + audioTimestampUs + ", " + systemClockUs + ", "
              + playbackPositionUs;
          if (failOnSpuriousAudioTimestamp) {
            throw new InvalidAudioTrackTimestampException(message);
          }
          log.w(message);
          audioTimestampSet = false;
        } else if (Math.abs(framesToDurationUs(audioTimestampFramePosition) - playbackPositionUs)
            > MAX_AUDIO_TIMESTAMP_OFFSET_US) {
          // The timestamp frame position is probably wrong.
          String message = "Spurious audio timestamp (frame position mismatch): "
              + audioTimestampFramePosition + ", " + audioTimestampUs + ", " + systemClockUs + ", "
              + playbackPositionUs;
          if (failOnSpuriousAudioTimestamp) {
            throw new InvalidAudioTrackTimestampException(message);
          }
          log.w(message);
          audioTimestampSet = false;
        }
      }
      // AMZN_CHANGE_BEGIN
      if(isLatencyQuirkEnabled) {
        // Get the audio h/w latency
        latencyUs = AmazonQuirks.getAudioHWLatency();
      } else if (getLatencyMethod != null) {
        try {
          // Compute the audio track latency, excluding the latency due to the buffer (leaving
          // latency due to the mixer and audio hardware driver).
          latencyUs = (Integer) getLatencyMethod.invoke(audioTrack, (Object[]) null) * 1000L
              - framesToDurationUs(bytesToFrames(bufferSize));
          // Sanity check that the latency is non-negative.
          latencyUs = Math.max(latencyUs, 0);
          // Sanity check that the latency isn't too large.
          if (latencyUs > MAX_LATENCY_US) {
            log.w("Ignoring impossibly large audio latency: " + latencyUs);
            latencyUs = 0;
          }
        } catch (Exception e) {
          // The method existed, but doesn't work. Don't try again.
          getLatencyMethod = null;
        }
      }
      // AMZN_CHANGE_END
      lastTimestampSampleTimeUs = systemClockUs;
    }
  }

  /**
   * Checks that {@link #audioTrack} has been successfully initialized. If it has then calling this
   * method is a no-op. If it hasn't then {@link #audioTrack} is released and set to null, and an
   * exception is thrown.
   *
   * @throws InitializationException If {@link #audioTrack} has not been successfully initialized.
   */
  private void checkAudioTrackInitialized() throws InitializationException {
    int state = audioTrack.getState();
    if (state == android.media.AudioTrack.STATE_INITIALIZED) {
      return;
    }
    // The track is not successfully initialized. Release and null the track.
    try {
      audioTrack.release();
    } catch (Exception e) {
      // The track has already failed to initialize, so it wouldn't be that surprising if release
      // were to fail too. Swallow the exception.
    } finally {
      audioTrack = null;
    }

    throw new InitializationException(state, sampleRate, channelConfig, bufferSize);
  }

  private long bytesToFrames(long byteCount) {
    if (isPassthrough()) {
      return passthroughBitrate == UNKNOWN_BITRATE
          ? 0L : byteCount * 8 * sampleRate / (1000 * passthroughBitrate);
    } else {
      return byteCount / frameSize;
    }
  }

  private long framesToDurationUs(long frameCount) {
    return (frameCount * C.MICROS_PER_SECOND) / sampleRate;
  }

  private long durationUsToFrames(long durationUs) {
    return (durationUs * sampleRate) / C.MICROS_PER_SECOND;
  }

  private void resetSyncParams() {
    smoothedPlayheadOffsetUs = 0;
    playheadOffsetCount = 0;
    nextPlayheadOffsetIndex = 0;
    lastPlayheadSampleTimeUs = 0;
    audioTimestampSet = false;
    lastTimestampSampleTimeUs = 0;
  }

  //AMZN_CHANGE_BEGIN
  // We can't use encoding value for JB OS based Fire TV Gen1 Family
  // Instead we cache the passthrough boolean parameter passed in reconfigure
  // and use it here.
  private boolean isPassthrough() {
    return isPassthrough;
    // Google's version
    // return isAc3Passthrough() || isDtsPassthrough();
  }

  private boolean isAc3Passthrough() {
    return encoding == C.ENCODING_AC3 || encoding == C.ENCODING_E_AC3;
  }

  private boolean isDtsPassthrough() {
    return encoding == C.ENCODING_DTS || encoding == C.ENCODING_DTS_HD;
  }

  /**
   * Returns whether to work around problems with passthrough audio tracks.
   * See [Internal: b/18899620, b/19187573, b/21145353].
   */
  private boolean needsPassthroughWorkarounds() {
    return Util.SDK_INT < 23 && isAc3Passthrough();
  }

  /**
   * Returns whether the audio track should behave as though it has pending data. This is to work
   * around an issue on platform API versions 21/22 where AC-3 audio tracks can't be paused, so we
   * empty their buffers when paused. In this case, they should still behave as if they have
   * pending data, otherwise writing will never resume.
   *
   * Additionally, in FireOS 4 based Amazon Tablets, after playback is resumed,
   * the play head position returned by AudioTrack during its stabilization phase
   * jumps to a high value, causing us to falsely believe that there is an under-run.
   * This workaround makes us behave as though AudioTrack has pending data irrespective of
   * the playback head position reported by AudioTrack til 1 sec after playback has resumed.
   */
  private boolean overrideHasPendingData() {
    //AMZN_CHANGE_BEGIN
    boolean hasPendingPassthroughData = needsPassthroughWorkarounds()
        && ( audioTrack.getPlayState() == android.media.AudioTrack.PLAYSTATE_PAUSED )
        && ( audioTrack.getPlaybackHeadPosition() == 0 );
    if (hasPendingPassthroughData) {
      return true;
    }
    boolean hasPendingDataQuirk = AmazonQuirks.isLatencyQuirkEnabled()
        && ( audioTrack.getPlayState() == android.media.AudioTrack.PLAYSTATE_PLAYING )
        && ( ((System.nanoTime() / 1000) - resumeSystemTimeUs) < C.MICROS_PER_SECOND );
    return hasPendingDataQuirk;
    //AMZN_CHANGE_END
  }

  private int getEncodingForMimeType(String mimeType) {
     // AMZN_CHANGE_BEGIN
    int encoding = AudioFormat.ENCODING_INVALID;
    switch (mimeType) {
      case MimeTypes.AUDIO_AC3:
        encoding = C.ENCODING_AC3;
        break;
      case MimeTypes.AUDIO_EC3:
        encoding = C.ENCODING_E_AC3;
        break;
      case MimeTypes.AUDIO_DTS:
        encoding = C.ENCODING_DTS;
        break;
      case MimeTypes.AUDIO_DTS_HD:
        encoding = C.ENCODING_DTS_HD;
        break;
      case MimeTypes.AUDIO_CUSTOM_EC3:
        encoding = C.ENCODING_E_AC3;
        break;
      default:
        encoding = AudioFormat.ENCODING_INVALID;
        break;
    }

    // If this method is found in AudioTrack,this device is Fire TV family
    // We use this to override the encoding format because in Fire TV Gen1 family the encodings
    // format have different value as compared to the ones introduced by Android in API21 onwards.
    if (getDirectTrackAudioFormat != null) {
      log.i("Invoking getDirectTrackAudioFormat with mimeType = " + mimeType +
              " and encoding format = " + encoding);
      try {
        encoding  = (Integer) getDirectTrackAudioFormat.invoke(android.media.AudioTrack.class,
                mimeType, encoding);
        log.i("We got new encoding format as " + encoding);
      } catch (Exception e) {
        log.e("Unable to access getDirectTrackAudioFormat", e);
      }
    }
    return encoding;
    // AMZN_CHANGE_END
  }

  /**
   * Wraps an {@link android.media.AudioTrack} to expose useful utility methods.
   */
  private static class AudioTrackUtil {

    protected android.media.AudioTrack audioTrack;
    private boolean needsPassthroughWorkaround;
    private int sampleRate;
    private long lastRawPlaybackHeadPosition;
    private long rawPlaybackHeadWrapCount;
    private long passthroughWorkaroundPauseOffset;

    private long stopTimestampUs;
    private long stopPlaybackHeadPosition;
    private long endPlaybackHeadPosition;

    /**
     * Reconfigures the audio track utility helper to use the specified {@code audioTrack}.
     *
     * @param audioTrack The audio track to wrap.
     * @param needsPassthroughWorkaround Whether to workaround issues with pausing AC-3 passthrough
     *     audio tracks on platform API version 21/22.
     */
    public void reconfigure(android.media.AudioTrack audioTrack,
        boolean needsPassthroughWorkaround) {
      this.audioTrack = audioTrack;
      this.needsPassthroughWorkaround = needsPassthroughWorkaround;
      stopTimestampUs = -1;
      lastRawPlaybackHeadPosition = 0;
      rawPlaybackHeadWrapCount = 0;
      passthroughWorkaroundPauseOffset = 0;
      if (audioTrack != null) {
        sampleRate = audioTrack.getSampleRate();
      }
    }

    /**
     * Stops the audio track in a way that ensures media written to it is played out in full, and
     * that {@link #getPlaybackHeadPosition()} and {@link #getPlaybackHeadPositionUs()} continue to
     * increment as the remaining media is played out.
     *
     * @param submittedFrames The total number of frames that have been submitted.
     */
    public void handleEndOfStream(long submittedFrames) {
      stopPlaybackHeadPosition = getPlaybackHeadPosition();
      stopTimestampUs = SystemClock.elapsedRealtime() * 1000;
      endPlaybackHeadPosition = submittedFrames;
      log.i("calling stop");
      audioTrack.stop();
    }

    /**
     * Pauses the audio track unless the end of the stream has been handled, in which case calling
     * this method does nothing.
     */
    public void pause() {
      if (stopTimestampUs != -1) {
        // We don't want to knock the audio track back into the paused state.
        return;
      }
      audioTrack.pause();
    }

    // AMZN_CHANGE_BEGIN
    private boolean isLatencyQuirkEnabled;
    private Method getLatencyMethod;
    private long   resumeTime;
    private Method getTimestamp;
    private AudioTimestamp audioTimestamp;

    private long rawTimestampFramePositionWrapCount;
    private long lastRawTimestampFramePosition;
    private long lastTimestampFramePosition;
    private final Logger log = new Logger(Logger.Module.Audio, TAG);
    public AudioTrackUtil(boolean isLatencyQuirkEnabled,
                            Method getLatencyMethod) {
      this.isLatencyQuirkEnabled = isLatencyQuirkEnabled;
      this.getLatencyMethod = getLatencyMethod;
      try {
        getTimestamp =
          android.media.AudioTrack.class.getMethod("getTimestamp",
                                                      AudioTimestamp.class);
      } catch (NoSuchMethodException e) {
        // There's no guarantee this method exists. Do nothing.
        log.w("getTimestamp method not found");
      } catch (NoClassDefFoundError e) {
        //We are using > 16 API so AudioTimestamp is not available
        log.w("AudioTimeStamp class not found");
      } catch(Throwable e) {
        log.w("AudioTimeStamp not found");
      }
    }

    private int getAudioSWLatencies() {
        int swLatencyFrames = 0;
        if (getLatencyMethod == null) {
            return 0;
        }
        try {
            Integer swLatencyMs = 0;
            swLatencyMs = (Integer) getLatencyMethod.invoke(audioTrack, (Object[]) null);
            swLatencyFrames = swLatencyMs * (sampleRate / 1000);
        } catch (Exception e) {
            return 0;
        }
        return swLatencyFrames;
    }

    public void play() {
        resumeTime = SystemClock.uptimeMillis();
    }
    /**
     * {@link android.media.AudioTrack#getPlaybackHeadPosition()} returns a value intended to be
     * interpreted as an unsigned 32 bit integer, which also wraps around periodically. This method
     * returns the playback head position as a long that will only wrap around if the value exceeds
     * {@link Long#MAX_VALUE} (which in practice will never happen).
     *
     * @return {@link android.media.AudioTrack#getPlaybackHeadPosition()} of {@link #audioTrack}
     *     expressed as a long.
     */
    public long getPlaybackHeadPosition() {
      if (stopTimestampUs != -1) {
        // Simulate the playback head position up to the total number of frames submitted.
        long elapsedTimeSinceStopUs = (SystemClock.elapsedRealtime() * 1000) - stopTimestampUs;
        long framesSinceStop = (elapsedTimeSinceStopUs * sampleRate) / C.MICROS_PER_SECOND;
        return Math.min(endPlaybackHeadPosition, stopPlaybackHeadPosition + framesSinceStop);
      }

      int state = audioTrack.getPlayState();
      if (state == android.media.AudioTrack.PLAYSTATE_STOPPED) {
        // The audio track hasn't been started.
        return 0;
      }

      long rawPlaybackHeadPosition = 0;
      if (isLatencyQuirkEnabled) {
        int php = audioTrack.getPlaybackHeadPosition();
        // if audio track includes latency while returning play head position
        // we try to compensate it back by adding the latency back to it,
        // if the track is in playing state or if pause state and php is non-zero
        int trackState = audioTrack.getPlayState();
        if(trackState == android.media.AudioTrack.PLAYSTATE_PLAYING ||
            (trackState == android.media.AudioTrack.PLAYSTATE_PAUSED && php != 0)) {
          php += getAudioSWLatencies();
        }
        if ( php < 0 && SystemClock.uptimeMillis() - resumeTime < C.MILLIS_PER_SECOND) {
            php = 0;
            log.i("php is negative during latency stablization phase ...resetting to 0");
        }
        rawPlaybackHeadPosition = 0xFFFFFFFFL & php;
        if (lastRawPlaybackHeadPosition > rawPlaybackHeadPosition &&
                lastRawPlaybackHeadPosition > 0x7FFFFFFFL &&
              (lastRawPlaybackHeadPosition - rawPlaybackHeadPosition >= 0x7FFFFFFFL) ) {
              // The value must have wrapped around.
          log.i("The playback head position wrapped around");
          rawPlaybackHeadWrapCount++;
        }
      } else {
        rawPlaybackHeadPosition = 0xFFFFFFFFL & audioTrack.getPlaybackHeadPosition();
        log.v("rawPlaybackHeadPosition = " + rawPlaybackHeadPosition);
        if (needsPassthroughWorkaround) {
          // Work around an issue with passthrough/direct AudioTracks on platform API versions 21/22
          // where the playback head position jumps back to zero on paused passthrough/direct audio
          // tracks. See [Internal: b/19187573].
          if (state == android.media.AudioTrack.PLAYSTATE_PAUSED && rawPlaybackHeadPosition == 0) {
            passthroughWorkaroundPauseOffset = lastRawPlaybackHeadPosition;
          }
          rawPlaybackHeadPosition += passthroughWorkaroundPauseOffset;
        }
        if (lastRawPlaybackHeadPosition > rawPlaybackHeadPosition) {
          // The value must have wrapped around.
          rawPlaybackHeadWrapCount++;
        }
      }
      lastRawPlaybackHeadPosition = rawPlaybackHeadPosition;
      return rawPlaybackHeadPosition + (rawPlaybackHeadWrapCount << 32);
    }
    // AMZN_CHANGE_END

    /**
     * Returns {@link #getPlaybackHeadPosition()} expressed as microseconds.
     */
    public long getPlaybackHeadPositionUs() {
      return (getPlaybackHeadPosition() * C.MICROS_PER_SECOND) / sampleRate;
    }
    // AMZN_CHANGE_BEGIN
    // JB OS of Fire TV Gen1 Family supports getTimeStamp API of API level 19
    /**
     * Updates the values returned by {@link #getTimestampNanoTime()} and
     * {@link #getTimestampFramePosition()}.
     *
     * @return True if the timestamp values were updated. False otherwise.
     */
    @TargetApi(19)
    public boolean updateTimestamp() {
      Boolean updated = false;
      if (getTimestamp == null) {
        return updated.booleanValue();
      }
      if (audioTimestamp == null) {
        audioTimestamp = new AudioTimestamp();
      }
      try {
        updated = (Boolean) getTimestamp.invoke(audioTrack, audioTimestamp);
      } catch ( Exception e) {
        log.e("getTimestamp exeception " , e);
      }
      if (updated) {
        long rawFramePosition = audioTimestamp.framePosition;
        if (lastRawTimestampFramePosition > rawFramePosition) {
          // The value must have wrapped around.
          rawTimestampFramePositionWrapCount++;
        }
        lastRawTimestampFramePosition = rawFramePosition;
        lastTimestampFramePosition = rawFramePosition + (rawTimestampFramePositionWrapCount << 32);
      }
      return updated.booleanValue();
    }
    // AMZN_CHANGE_END
    /**
     * Returns the {@link android.media.AudioTimestamp#nanoTime} obtained during the most recent
     * call to {@link #updateTimestamp()} that returned true.
     *
     * @return The nanoTime obtained during the most recent call to {@link #updateTimestamp()} that
     *     returned true.
     * @throws UnsupportedOperationException If the implementation does not support audio timestamp
     *     queries. {@link #updateTimestamp()} will always return false in this case.
     */
    @TargetApi(19)
    public long getTimestampNanoTime() {
      if (getTimestamp == null) {
        // Should never be called if updateTimestamp() returned false.
        throw new UnsupportedOperationException();
      } else {
        return audioTimestamp.nanoTime;
      }
    }

    /**
     * Returns the {@link android.media.AudioTimestamp#framePosition} obtained during the most
     * recent call to {@link #updateTimestamp()} that returned true. The value is adjusted so that
     * wrap around only occurs if the value exceeds {@link Long#MAX_VALUE} (which in practice will
     * never happen).
     *
     * @return The framePosition obtained during the most recent call to {@link #updateTimestamp()}
     *     that returned true.
     * @throws UnsupportedOperationException If the implementation does not support audio timestamp
     *     queries. {@link #updateTimestamp()} will always return false in this case.
     */
    public long getTimestampFramePosition() {
      // Should never be called if updateTimestamp() returned false.
      if (getTimestamp == null) {
        throw new UnsupportedOperationException();
      } else {
        return lastTimestampFramePosition;
      }
    }

  }

  @TargetApi(19)
  private static class AudioTrackUtilV19 extends AudioTrackUtil {

    private final AudioTimestamp audioTimestamp;

    private long rawTimestampFramePositionWrapCount;
    private long lastRawTimestampFramePosition;
    private long lastTimestampFramePosition;

    // AMZN_CHANGE_BEGIN
    public AudioTrackUtilV19(boolean isLatencyQuirkEnabled,
                            Method getLatencyMethod) {
      super(isLatencyQuirkEnabled,getLatencyMethod);
      audioTimestamp = new AudioTimestamp();
    }
    // AMZN_CHANGE_END

    @Override
    public void reconfigure(android.media.AudioTrack audioTrack,
        boolean needsPassthroughWorkaround) {
      super.reconfigure(audioTrack, needsPassthroughWorkaround);
      rawTimestampFramePositionWrapCount = 0;
      lastRawTimestampFramePosition = 0;
      lastTimestampFramePosition = 0;
    }

    @Override
    public boolean updateTimestamp() {
      boolean updated = audioTrack.getTimestamp(audioTimestamp);
      if (updated) {
        long rawFramePosition = audioTimestamp.framePosition;
        if (lastRawTimestampFramePosition > rawFramePosition) {
          // The value must have wrapped around.
          rawTimestampFramePositionWrapCount++;
        }
        lastRawTimestampFramePosition = rawFramePosition;
        lastTimestampFramePosition = rawFramePosition + (rawTimestampFramePositionWrapCount << 32);
      }
      return updated;
    }

    @Override
    public long getTimestampNanoTime() {
      return audioTimestamp.nanoTime;
    }

    @Override
    public long getTimestampFramePosition() {
      return lastTimestampFramePosition;
    }

  }

}
