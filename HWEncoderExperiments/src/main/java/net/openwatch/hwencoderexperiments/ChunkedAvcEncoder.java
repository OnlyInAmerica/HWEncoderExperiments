package net.openwatch.hwencoderexperiments;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Created by davidbrodsky on 9/12/13.
 * Enormous thanks to Andrew McFadden for his MediaCodec examples!
 * Adapted from http://bigflake.com/mediacodec/CameraToMpegTest.java.txt
 */
public class ChunkedAvcEncoder {
    private static final String TAG = "ChunkedAvcEncoder";
    private static final String VIDEO_MIME_TYPE = "video/avc";
    private static final String AUDIO_MIME_TYPE = "audio/mp4a-latm";
    private static final boolean VERBOSE = false;
    private MediaFormat videoFormat;
    private MediaCodec mVideoEncoder;
    private TrackIndex mVideoTrackIndex = new TrackIndex();
    private MediaFormat audioFormat;
    private MediaCodec mAudioEncoder;
    private TrackIndex mAudioTrackIndex = new TrackIndex();
    private MediaMuxer mMuxer;
    private boolean mMuxerStarted;
    private MediaCodec.BufferInfo mVideoBufferInfo;
    private MediaCodec.BufferInfo mAudioBufferInfo;
    boolean eosReceived = false;
    boolean eosSentToVideoEncoder = false;
    boolean eosSentToAudioEncoder = false;
    boolean stopReceived = false;
    long videoStartTime = 0;
    long audioStartTime = 0;

    // Chunk state
    final int CHUNK_DURATION_SEC = 5;
    final int FRAMES_PER_SECOND = 30;
    int currentChunk = 1;
    int frameCount = 0;
    int framesPerChunk = 0;     // calculated
    int totalVideoFrameCount = 0;
    int totalInputAudioFrameCount = 0; // testing
    int totalOutputAudioFrameCount = 0;

    // Audio state
    private static long audioBytesReceived = 0;

    // Muxer state
    private static final int TOTAL_NUM_TRACKS = 2;
    private static int numTracksAdded = 0;

    Context c;

    private ExecutorService encodingService = Executors.newSingleThreadExecutor(); // re-use encodingService
    int encodingServiceQueueLength = 0;

    // Can't pass an int by reference in Java...
    class TrackIndex{
        int index = 0;
    }

    public ChunkedAvcEncoder(Context c) {
        this.c = c;
        prepare();
    }

    private void prepare(){
        audioBytesReceived = 0;
        numTracksAdded = 0;
        frameCount = 0;
        eosReceived = false;
        eosSentToVideoEncoder = false;
        eosSentToAudioEncoder = false;
        stopReceived = false;
        framesPerChunk = CHUNK_DURATION_SEC * FRAMES_PER_SECOND;
        File f = FileUtils.createTempFileInRootAppStorage(c, "test_" + currentChunk + ".mp4");

        mVideoBufferInfo = new MediaCodec.BufferInfo();

        videoFormat = MediaFormat.createVideoFormat(VIDEO_MIME_TYPE, 640, 480);
        videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, 250000);
        videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, FRAMES_PER_SECOND);
        videoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_TI_FormatYUV420PackedSemiPlanar);
        // COLOR_TI_FormatYUV420PackedSemiPlanar works - wrong hue
        // COLOR_FormatYUV420PackedSemiPlanar - crash
        // COLOR_FormatYUV420Planar - crash
        videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5);

        mVideoEncoder = MediaCodec.createEncoderByType(VIDEO_MIME_TYPE);
        mVideoEncoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mVideoEncoder.start();

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
            /*mVideoTrackIndex.index = mMuxer.addTrack(videoFormat);
            mAudioTrackIndex.index = mMuxer.addTrack(audioFormat);
            Log.i(TAG, "Added tracks. Video: " + mVideoTrackIndex.index + " Audio: " + mAudioTrackIndex.index);
            mMuxer.start();
            mMuxerStarted = true;*/
        } catch (IOException ioe) {
            throw new RuntimeException("MediaMuxer creation failed", ioe);
        }
    }

    public void stop(){
        if(!encodingService.isShutdown())
            encodingService.submit(new EncoderTask(this, EncoderTaskType.FINALIZE_ENCODER));
    }

    /**
     * Called from encodingService
     */
    public void _stop(){
        stopReceived = true;
        eosReceived = true;
        Log.i(TAG, "ChunkedAVEnc saw #frames: " + totalVideoFrameCount);
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

    public void closeMuxer(){
        mMuxer.stop();
        mMuxer.release();
        mMuxer = null;
        mMuxerStarted = false;
    }

    /**
     * External method called by CameraPreviewCallback or similar
     * @param input
     */
    public void offerVideoEncoder(byte[] input){
        if(!encodingService.isShutdown()){
            long thisFrameTime = System.nanoTime();
            encodingService.submit(new EncoderTask(this, input, null, thisFrameTime));
            encodingServiceQueueLength ++;
        }

    }

    /**
     * Internal method called by encodingService
     * @param input
     */
    private void _offerVideoEncoder(byte[] input, long presentationTimeNs) {
        if(frameCount == 0)
            videoStartTime = presentationTimeNs;
        if(eosSentToVideoEncoder && stopReceived){
            logStatistics();
            return;
        }

        totalVideoFrameCount++;
        frameCount ++;

        if(frameCount % framesPerChunk == 0){
            Log.i(TAG, "Chunking...");
            eosReceived = true;
        }

        // transfer previously encoded data to muxer
        drainEncoder(mVideoEncoder, mVideoBufferInfo, mVideoTrackIndex, false);
        // send current frame data to encoder
        try {
            ByteBuffer[] inputBuffers = mVideoEncoder.getInputBuffers();
            int inputBufferIndex = mVideoEncoder.dequeueInputBuffer(-1);
            if (inputBufferIndex >= 0) {
                ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
                inputBuffer.clear();
                inputBuffer.put(input);
                long presentationTimeUs = (presentationTimeNs - videoStartTime) / 1000;
                //Log.i(TAG, "Attempt to set PTS: " + presentationTimeUs);
                if(eosReceived){
                    Log.i(TAG, "EOS received in offerEncoder");
                    mVideoEncoder.queueInputBuffer(inputBufferIndex, 0, input.length, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    closeEncoder(mVideoEncoder, mVideoBufferInfo, mVideoTrackIndex);
                    eosSentToVideoEncoder = true;
                }else
                    mVideoEncoder.queueInputBuffer(inputBufferIndex, 0, input.length, presentationTimeUs, 0);
            }
        } catch (Throwable t) {
            Log.e(TAG, "_offerVideoEncoder exception");
            t.printStackTrace();
        }
    }

    /**
     * temp restriction: Always call after offerVideoEncoder
     * @param input
     */
    public void offerAudioEncoder(byte[] input, long presentationTimeStampNs){
        if(!encodingService.isShutdown()){
            //long thisFrameTime = (presentationTimeNs == 0) ? System.nanoTime() : presentationTimeNs;
            encodingService.submit(new EncoderTask(this, null, input, presentationTimeStampNs));
            encodingServiceQueueLength++;
        }

    }

    /**
     * temp restriction: Always call after _offerVideoEncoder
     * @param input
     * @param presentationTimeNs
     */
    private void _offerAudioEncoder(byte[] input, long presentationTimeNs) {
        if(audioBytesReceived == 0){
            audioStartTime = presentationTimeNs;
        }
        totalInputAudioFrameCount++;
        audioBytesReceived += input.length;
        if(eosSentToAudioEncoder && stopReceived || input == null){
            logStatistics();
            if(eosReceived){
                Log.i(TAG, "EOS received in offerAudioEncoder");
                closeEncoderAndMuxer(mAudioEncoder, mAudioBufferInfo, mAudioTrackIndex); // always called after video, so safe to close muxer // TODO: re-evaluate
                eosSentToAudioEncoder = true;
                if(!stopReceived){
                    // swap encoder
                    currentChunk++;
                    prepare();
                }else{
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
                long presentationTimeUs = (presentationTimeNs - audioStartTime) / 1000;
                //Log.i(TAG, "sent " + input.length + " audio bytes to encod with pts " + presentationTimeUs);
                //nextQueuedAudioPresentationTimeStampUs = getNextAudioQueuedPresentationTimeStampUs((presentationTimeNs - audioStartTime) / 1000, input.length);
                if(eosReceived){
                    Log.i(TAG, "EOS received in offerEncoder");
                    mAudioEncoder.queueInputBuffer(inputBufferIndex, 0, input.length, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    closeEncoderAndMuxer(mAudioEncoder, mAudioBufferInfo, mAudioTrackIndex); // always called after video, so safe to close muxer
                    eosSentToAudioEncoder = true;
                    if(!stopReceived){
                        // swap encoder
                        currentChunk++;
                        prepare();
                    }else{
                        Log.i(TAG, "Stopping Encoding Service");
                        encodingService.shutdown();
                    }
                }else{
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
        final int TIMEOUT_USEC = 1000;
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
                numTracksAdded ++;
                Log.d(TAG, "encoder output format changed: " + newFormat +". Added track index: " + trackIndex.index);
                if(numTracksAdded == TOTAL_NUM_TRACKS){
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
                    if (VERBOSE) Log.d(TAG, "sent " + bufferInfo.size + ((encoder == mVideoEncoder) ? " video" : " audio") + " bytes to muxer with pts " + bufferInfo.presentationTimeUs);
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

    private void logStatistics(){
        Log.i(TAG + "-Stats", "audio frames input: " + totalInputAudioFrameCount + " output: " + totalOutputAudioFrameCount);
    }


    enum EncoderTaskType{
        ENCODE_FRAME, /*SHIFT_ENCODER,*/ FINALIZE_ENCODER;
    }

    private class EncoderTask implements Runnable{
        private static final String TAG = "encoderTask";

        private ChunkedAvcEncoder encoder;

        private EncoderTaskType type;
        boolean is_initialized = false;

        // vars for type ENCODE_FRAME
        private byte[] video_data;
        private byte[] audio_data;
        long presentationTimeNs;

        public EncoderTask(ChunkedAvcEncoder encoder, EncoderTaskType type){
            setEncoder(encoder);
            this.type = type;
            switch(type){
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

        public EncoderTask(ChunkedAvcEncoder encoder, byte[] video_data, byte[] audio_data, long pts){
            setEncoder(encoder);
            setEncodeFrameParams(video_data, audio_data, pts);
        }

        public EncoderTask(ChunkedAvcEncoder encoder){
            setEncoder(encoder);
            setFinalizeEncoderParams();
        }

        private void setEncoder(ChunkedAvcEncoder encoder){
            this.encoder = encoder;
        }

        private void setFinalizeEncoderParams(){
            is_initialized = true;
        }

        private void setEncodeFrameParams(byte[] video_data, byte[] audio_data, long pts){
            this.video_data = video_data;
            this.audio_data = audio_data;
            this.presentationTimeNs = pts;

            is_initialized = true;
            this.type = EncoderTaskType.ENCODE_FRAME;
        }

        /*
        private void setShiftEncoderParams(){
            this.type = EncoderTaskType.SHIFT_ENCODER;
            is_initialized = true;
        }
        */

        private void encodeFrame(){
            if(encoder != null && video_data != null){
                encoder._offerVideoEncoder(video_data, presentationTimeNs);
                video_data = null;
            }
            else if(encoder != null && audio_data != null){
                encoder._offerAudioEncoder(audio_data, presentationTimeNs);
                audio_data = null;
            }
        }

        private void finalizeEncoder(){
            encoder._stop();
        }

        @Override
        public void run() {
            if(is_initialized){
                switch(type){
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
                encodingServiceQueueLength -=1;
            }
            else{
                Log.e(TAG, "run() called but EncoderTask not initialized");
            }

        }

    }
}