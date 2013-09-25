package net.openwatch.hwencoderexperiments;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Created by davidbrodsky on 9/12/13.
 * Enormous thanks to Andrew McFadden for his MediaCodec examples!
 * Adapted from http://bigflake.com/mediacodec/CameraToMpegTest.java.txt
 */
public class AudioEncoder {
    private static final String TAG = "AudioEncoder";
    private static final String AUDIO_MIME_TYPE = "audio/mp4a-latm";
    private static final boolean VERBOSE = false;
    // Muxer state
    private static final int TOTAL_NUM_TRACKS = 1;
    // Audio state
    private static long audioBytesReceived = 0;
    private static int numTracksAdded = 0;
    boolean eosReceived = false;
    boolean eosSentToAudioEncoder = false;
    boolean stopReceived = false;
    long audioStartTime = 0;
    int frameCount = 0;
    int totalInputAudioFrameCount = 0; // testing
    int totalOutputAudioFrameCount = 0;
    Context c;
    int encodingServiceQueueLength = 0;
    private MediaFormat audioFormat;
    private MediaCodec mAudioEncoder;
    private TrackIndex mAudioTrackIndex = new TrackIndex();
    private MediaMuxer mMuxer;
    private boolean mMuxerStarted;
    private MediaCodec.BufferInfo mAudioBufferInfo;
    private ExecutorService encodingService = Executors.newSingleThreadExecutor(); // re-use encodingService

    AudioSoftwarePoller audioSoftwarePoller;

    public AudioEncoder(Context c) {
        this.c = c;
        prepare();
    }

    public void setAudioSoftwarePoller(AudioSoftwarePoller audioSoftwarePoller){
        this.audioSoftwarePoller = audioSoftwarePoller;
    }


    private void prepare() {
        audioBytesReceived = 0;
        numTracksAdded = 0;
        frameCount = 0;
        eosReceived = false;
        eosSentToAudioEncoder = false;
        stopReceived = false;
        File f = FileUtils.createTempFileInRootAppStorage(c, "test_" + new Date().getTime() + ".m4a");
        Toast.makeText(c, "Saving audio to: " + f.getAbsolutePath(), Toast.LENGTH_LONG).show();

        mAudioBufferInfo = new MediaCodec.BufferInfo();

        audioFormat = new MediaFormat();
        audioFormat.setString(MediaFormat.KEY_MIME, AUDIO_MIME_TYPE);
        audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        audioFormat.setInteger(MediaFormat.KEY_SAMPLE_RATE, 44100);
        audioFormat.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1);
        audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, 128000);
        audioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);

        mAudioEncoder = MediaCodec.createEncoderByType(AUDIO_MIME_TYPE);
        mAudioEncoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mAudioEncoder.start();

        try {
            mMuxer = new MediaMuxer(f.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        } catch (IOException ioe) {
            throw new RuntimeException("MediaMuxer creation failed", ioe);
        }
    }

    public void stop() {
        if (!encodingService.isShutdown())
            encodingService.submit(new EncoderTask(this, EncoderTaskType.FINALIZE_ENCODER));
    }

    /**
     * Called from encodingService
     */
    public void _stop() {
        stopReceived = true;
        eosReceived = true;
        logStatistics();
    }

    public void closeEncoderAndMuxer(MediaCodec encoder, MediaCodec.BufferInfo bufferInfo, TrackIndex trackIndex) {
        drainEncoder(encoder, bufferInfo, trackIndex, true);
        try {
            encoder.stop();
            encoder.release();
            encoder = null;
            closeMuxer();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void closeEncoder(MediaCodec encoder, MediaCodec.BufferInfo bufferInfo, TrackIndex trackIndex) {
        drainEncoder(encoder, bufferInfo, trackIndex, true);
        try {
            encoder.stop();
            encoder.release();
            encoder = null;
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void closeMuxer() {
        mMuxer.stop();
        mMuxer.release();
        mMuxer = null;
        mMuxerStarted = false;
    }

    /**
     * temp restriction: Always call after offerVideoEncoder
     *
     * @param input
     */
    public void offerAudioEncoder(byte[] input, long presentationTimeStampNs) {
        if (!encodingService.isShutdown()) {
            //long thisFrameTime = (presentationTimeNs == 0) ? System.nanoTime() : presentationTimeNs;
            encodingService.submit(new EncoderTask(this, input, presentationTimeStampNs));
            encodingServiceQueueLength++;
        }

    }

    /**
     * temp restriction: Always call after _offerVideoEncoder
     *
     * @param input
     * @param presentationTimeNs
     */
    private void _offerAudioEncoder(byte[] input, long presentationTimeNs) {
        if (audioBytesReceived == 0) {
            audioStartTime = presentationTimeNs;
        }
        totalInputAudioFrameCount++;
        audioBytesReceived += input.length;
        if (eosSentToAudioEncoder && stopReceived || input == null) {
            logStatistics();
            if (eosReceived) {
                Log.i(TAG, "EOS received in offerAudioEncoder");
                closeEncoderAndMuxer(mAudioEncoder, mAudioBufferInfo, mAudioTrackIndex);
                eosSentToAudioEncoder = true;
                if (!stopReceived) {
                    // swap encoder
                    prepare();
                } else {
                    Log.i(TAG, "Stopping Encoding Service");
                    encodingService.shutdown();
                }
            }
            return;
        }
        // transfer previously encoded data to muxer
        drainEncoder(mAudioEncoder, mAudioBufferInfo, mAudioTrackIndex, false);
        // send current frame data to encoder
        try {
            ByteBuffer[] inputBuffers = mAudioEncoder.getInputBuffers();
            int inputBufferIndex = mAudioEncoder.dequeueInputBuffer(-1);
            if (inputBufferIndex >= 0) {
                ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
                inputBuffer.clear();
                inputBuffer.put(input);
                if(audioSoftwarePoller != null){
                    audioSoftwarePoller.recycleInputBuffer(input);
                }
                long presentationTimeUs = (presentationTimeNs - audioStartTime) / 1000;
                if (eosReceived) {
                    Log.i(TAG, "EOS received in offerEncoder");
                    mAudioEncoder.queueInputBuffer(inputBufferIndex, 0, input.length, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    closeEncoderAndMuxer(mAudioEncoder, mAudioBufferInfo, mAudioTrackIndex); // always called after video, so safe to close muxer
                    eosSentToAudioEncoder = true;
                    if (stopReceived) {
                        Log.i(TAG, "Stopping Encoding Service");
                        encodingService.shutdown();
                    }
                } else {
                    mAudioEncoder.queueInputBuffer(inputBufferIndex, 0, input.length, presentationTimeUs, 0);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "_offerAudioEncoder exception");
            t.printStackTrace();
        }
    }

    /**
     * Extracts all pending data from the encoder and forwards it to the muxer.
     * <p/>
     * If endOfStream is not set, this returns when there is no more data to drain.  If it
     * is set, we send EOS to the encoder, and then iterate until we see EOS on the output.
     * Calling this with endOfStream set should be done once, right before stopping the muxer.
     * <p/>
     * We're just using the muxer to get a .mp4 file (instead of a raw H.264 stream).  We're
     * not recording audio.
     */
    private void drainEncoder(MediaCodec encoder, MediaCodec.BufferInfo bufferInfo, TrackIndex trackIndex, boolean endOfStream) {
        final int TIMEOUT_USEC = 100;
        if (VERBOSE) Log.d(TAG, "drainEncoder(" + endOfStream + ")");
        ByteBuffer[] encoderOutputBuffers = encoder.getOutputBuffers();
        while (true) {
            int encoderStatus = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC);
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // no output available yet
                if (!endOfStream) {
                    break;      // out of while
                } else {
                    if (VERBOSE) Log.d(TAG, "no output available, spinning to await EOS");
                }
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                // not expected for an encoder
                encoderOutputBuffers = encoder.getOutputBuffers();
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // should happen before receiving buffers, and should only happen once

                if (mMuxerStarted) {
                    throw new RuntimeException("format changed after muxer start");
                }
                MediaFormat newFormat = encoder.getOutputFormat();

                // now that we have the Magic Goodies, start the muxer
                trackIndex.index = mMuxer.addTrack(newFormat);
                numTracksAdded++;
                Log.d(TAG, "encoder output format changed: " + newFormat + ". Added track index: " + trackIndex.index);
                if (numTracksAdded == TOTAL_NUM_TRACKS) {
                    mMuxer.start();
                    mMuxerStarted = true;
                    Log.i(TAG, "All tracks added. Muxer started");
                }

            } else if (encoderStatus < 0) {
                Log.w(TAG, "unexpected result from encoder.dequeueOutputBuffer: " +
                        encoderStatus);
                // let's ignore it
            } else {
                ByteBuffer encodedData = encoderOutputBuffers[encoderStatus];
                if (encodedData == null) {
                    throw new RuntimeException("encoderOutputBuffer " + encoderStatus +
                            " was null");
                }


                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    // The codec config data was pulled out and fed to the muxer when we got
                    // the INFO_OUTPUT_FORMAT_CHANGED status.  Ignore it.
                    if (VERBOSE) Log.d(TAG, "ignoring BUFFER_FLAG_CODEC_CONFIG");
                    bufferInfo.size = 0;
                }


                if (bufferInfo.size != 0) {
                    if (!mMuxerStarted) {
                        throw new RuntimeException("muxer hasn't started");
                    }

                    // adjust the ByteBuffer values to match BufferInfo (not needed?)
                    encodedData.position(bufferInfo.offset);
                    encodedData.limit(bufferInfo.offset + bufferInfo.size);
                    mMuxer.writeSampleData(trackIndex.index, encodedData, bufferInfo);
                }

                encoder.releaseOutputBuffer(encoderStatus, false);

                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    if (!endOfStream) {
                        Log.w(TAG, "reached end of stream unexpectedly");
                    } else {
                        if (VERBOSE) Log.d(TAG, "end of stream reached");
                    }
                    break;      // out of while
                }
            }
        }
        long endTime = System.nanoTime();
    }

    private void logStatistics() {
        Log.i(TAG + "-Stats", "audio frames input: " + totalInputAudioFrameCount + " output: " + totalOutputAudioFrameCount);
    }

    enum EncoderTaskType {
        ENCODE_FRAME, /*SHIFT_ENCODER,*/ FINALIZE_ENCODER;
    }

    // Can't pass an int by reference in Java...
    class TrackIndex {
        int index = 0;
    }

    private class EncoderTask implements Runnable {
        private static final String TAG = "encoderTask";
        boolean is_initialized = false;
        long presentationTimeNs;
        private AudioEncoder encoder;
        private EncoderTaskType type;
        private byte[] audio_data;

        public EncoderTask(AudioEncoder encoder, EncoderTaskType type) {
            setEncoder(encoder);
            this.type = type;
            switch (type) {
                /*
                case SHIFT_ENCODER:
                    setShiftEncoderParams();
                    break;
                */
                case FINALIZE_ENCODER:
                    setFinalizeEncoderParams();
                    break;
            }
        }

        public EncoderTask(AudioEncoder encoder, byte[] audio_data, long pts) {
            setEncoder(encoder);
            setEncodeFrameParams(audio_data, pts);
        }

        public EncoderTask(AudioEncoder encoder) {
            setEncoder(encoder);
            setFinalizeEncoderParams();
        }

        private void setEncoder(AudioEncoder encoder) {
            this.encoder = encoder;
        }

        private void setFinalizeEncoderParams() {
            is_initialized = true;
        }

        private void setEncodeFrameParams(byte[] audio_data, long pts) {
            this.audio_data = audio_data;
            this.presentationTimeNs = pts;

            is_initialized = true;
            this.type = EncoderTaskType.ENCODE_FRAME;
        }

        private void encodeFrame() {
        if (encoder != null && audio_data != null) {
                encoder._offerAudioEncoder(audio_data, presentationTimeNs);
                audio_data = null;
            }
        }

        private void finalizeEncoder() {
            encoder._stop();
        }

        @Override
        public void run() {
            if (is_initialized) {
                switch (type) {
                    case ENCODE_FRAME:
                        encodeFrame();
                        break;
                    /*
                    case SHIFT_ENCODER:
                        shiftEncoder();
                        break;
                    */
                    case FINALIZE_ENCODER:
                        finalizeEncoder();
                        break;

                }
                // prevent multiple execution of same task
                is_initialized = false;
                encodingServiceQueueLength -= 1;
                //Log.i(TAG, "EncodingService Queue length: " + encodingServiceQueueLength);
            } else {
                Log.e(TAG, "run() called but EncoderTask not initialized");
            }

        }

    }
}