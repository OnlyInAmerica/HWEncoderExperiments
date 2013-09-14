package net.openwatch.hwencoderexperiments;

import android.content.Context;
import android.media.*;
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
    private static final boolean VERBOSE = true;
    private MediaFormat videoFormat;
    private MediaFormat audioFormat;
    private MediaCodec mVideoEncoder;
    private MediaCodec mAudioEncoder;
    private MediaMuxer mMuxer;
    private int mVideoTrackIndex;
    private int mAudioTrackIndex;
    private boolean mMuxerStarted;
    // allocate one of these up front so we don't need to do it every time
    private MediaCodec.BufferInfo mBufferInfo;
    boolean eosReceived = false;
    boolean eosSentToEncoder = false;
    boolean stopReceived = false;
    long startTime = 0;

    // Chunk state
    final int CHUNK_DURATION_SEC = 5;
    final int FRAMES_PER_SECOND = 30;
    int currentChunk = 1;
    int frameCount = 0;
    int framesPerChunk = 0;     // calculated
    int totalFrameCount = 0;


    Context c;

    private ExecutorService encodingService = Executors.newSingleThreadExecutor(); // re-use encodingService

    public ChunkedAvcEncoder(Context c) {
        this.c = c;
        prepare();
    }

    private void prepare(){
        frameCount = 0;
        eosReceived = false;
        eosSentToEncoder = false;
        framesPerChunk = CHUNK_DURATION_SEC * FRAMES_PER_SECOND;
        File f = FileUtils.createTempFileInRootAppStorage(c, "test_" + currentChunk + ".mp4");

        mBufferInfo = new MediaCodec.BufferInfo();

        videoFormat = MediaFormat.createVideoFormat(VIDEO_MIME_TYPE, 640, 480);
        videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, 250000);
        videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, FRAMES_PER_SECOND);
        videoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_TI_FormatYUV420PackedSemiPlanar);
        videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5);

        mVideoEncoder = MediaCodec.createEncoderByType(VIDEO_MIME_TYPE);
        mVideoEncoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mVideoEncoder.start();

        audioFormat = new MediaFormat();
        audioFormat.setString(MediaFormat.KEY_MIME, AUDIO_MIME_TYPE);
        audioFormat.setInteger(MediaFormat.KEY_SAMPLE_RATE, 44100);
        audioFormat.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1);
        audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, 96000);
        audioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 11264);


        mAudioEncoder = MediaCodec.createEncoderByType(AUDIO_MIME_TYPE);
        mAudioEncoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mAudioEncoder.start();

        try {
            mMuxer = new MediaMuxer(f.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            mVideoTrackIndex = mMuxer.addTrack(videoFormat);
            mAudioTrackIndex = mMuxer.addTrack(audioFormat);
            mMuxer.start();
            mMuxerStarted = true;
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
        Log.i(TAG, "ChunkedAVEnc saw #frames: " + totalFrameCount);
    }

    public void close() {
        drainEncoders(true);
        try {
            mAudioEncoder.stop();
            mAudioEncoder.release();
            mAudioEncoder = null;
            mVideoEncoder.stop();
            mVideoEncoder.release();
            mVideoEncoder = null;
            mMuxer.stop();
            mMuxer.release();
            mMuxer = null;
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    long lastFrameTime = -1;
    /**
     * External method called by CameraPreviewCallback or similar
     * @param videoFrame
     */
    public void offerEncoder(byte[] videoFrame, byte[] audioFrames){
        if(!encodingService.isShutdown()){
            if(VERBOSE) Log.i(TAG, "offerEncoder with video: " + videoFrame.length + " audio: " + ( (audioFrames == null) ? 0 : audioFrames.length));
            long thisFrameTime = System.nanoTime();
            encodingService.submit(new EncoderTask(this, videoFrame, audioFrames, thisFrameTime));
            lastFrameTime = System.nanoTime();
        }

    }

    /**
     * Internal method called by encodingService
     * @param videoFrame
     */
    private void _offerEncoder(byte[] videoFrame, byte[] audioFrames, long presentationTimeNs) {
        if(VERBOSE) Log.i(TAG, "_offerEncoder with video: " + videoFrame.length + " audio: " + ( (audioFrames == null) ? 0 : audioFrames.length));
        if(frameCount == 0)
            startTime = presentationTimeNs;
        if(eosSentToEncoder && stopReceived){
            return;
        }

        totalFrameCount ++;
        frameCount ++;

        if(frameCount % framesPerChunk == 0){
            Log.i(TAG, "Chunking...");
            eosReceived = true;
        }

        // transfer previously encoded data to muxer
        drainEncoders(false);
        // send current frame data to mVideoEncoder
        try {
            ByteBuffer[] videoInputBuffers = mVideoEncoder.getInputBuffers();
            ByteBuffer[] audioInputBuffers = mAudioEncoder.getInputBuffers();
            int videoInputBufferIndex = mVideoEncoder.dequeueInputBuffer(-1);
            int audioInputBufferIndex = mAudioEncoder.dequeueInputBuffer(-1);
            if (audioFrames != null && audioInputBufferIndex >= 0) {
                ByteBuffer audioInputBuffer = audioInputBuffers[audioInputBufferIndex];
                audioInputBuffer.clear();
                audioInputBuffer.put(audioFrames);
                if(eosReceived){
                    mAudioEncoder.queueInputBuffer(audioInputBufferIndex, 0, audioFrames.length, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                }else
                    mAudioEncoder.queueInputBuffer(audioInputBufferIndex, 0, audioFrames.length, 0, 0);
            }
            if (videoInputBufferIndex >= 0) {
                ByteBuffer videoInputBuffer = videoInputBuffers[videoInputBufferIndex];
                videoInputBuffer.clear();
                videoInputBuffer.put(videoFrame);
                long presentationTimeUs = (presentationTimeNs - startTime) / 1000;
                //Log.i(TAG, "Attempt to set PTS: " + presentationTimeUs);
                if(eosReceived){
                    Log.i(TAG, "EOS received in offerEncoder");
                    mVideoEncoder.queueInputBuffer(videoInputBufferIndex, 0, videoFrame.length, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    close();
                    eosSentToEncoder = true;
                    if(!stopReceived){
                        currentChunk++;
                        prepare();
                    }else{
                        Log.i(TAG, "Stopping Encoding Service");
                        encodingService.shutdown();
                    }
                }else
                    mVideoEncoder.queueInputBuffer(videoInputBufferIndex, 0, videoFrame.length, presentationTimeUs, 0);
            }
        } catch (Throwable t) {
            if(VERBOSE) Log.e(TAG, "_offerEncoder exception");
            t.printStackTrace();
        }
    }

    /**
     * Extracts all pending data from the mVideoEncoder and forwards it to the muxer.
     * <p/>
     * If endOfStream is not set, this returns when there is no more data to drain.  If it
     * is set, we send EOS to the mVideoEncoder, and then iterate until we see EOS on the output.
     * Calling this with endOfStream set should be done once, right before stopping the muxer.
     * <p/>
     * We're just using the muxer to get a .mp4 file (instead of a raw H.264 stream).  We're
     * not recording audio.
     */
    private void drainEncoders(boolean endOfStream) {
        if (VERBOSE) Log.d(TAG, "drainEncoders(" + endOfStream + ")");
        drainEncoder(mAudioEncoder, endOfStream);
        drainEncoder(mVideoEncoder, endOfStream);
    }

    private void drainEncoder(MediaCodec encoder, boolean endOfStream){
        final int TIMEOUT_USEC = 10000;
        boolean isVideoEncoder = (encoder == mVideoEncoder);
        if(VERBOSE) Log.i(TAG, "drainEncoder video: " + isVideoEncoder);
        ByteBuffer[] encoderOutputBuffers = encoder.getOutputBuffers();
        while (true) {
            int encoderStatus = encoder.dequeueOutputBuffer(mBufferInfo, TIMEOUT_USEC);
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // no output available yet
                if (!endOfStream) {
                    break;      // out of while
                } else {
                    if (VERBOSE) Log.d(TAG, "no output available, spinning to await EOS");
                }
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                // not expected for an mVideoEncoder
                encoderOutputBuffers = encoder.getOutputBuffers();
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // should happen before receiving buffers, and should only happen once
                MediaFormat newFormat = encoder.getOutputFormat();
                Log.d(TAG, "encoder output format changed: " + newFormat);

                /*
                if (mMuxerStarted) {
                    //throw new RuntimeException("format changed twice");
                }

                if(encoder == mVideoEncoder){
                    mVideoTrackIndex = mMuxer.addTrack(newFormat);
                    mMuxer.start();
                    mMuxerStarted = true;
                }else
                    mAudioTrackIndex = mMuxer.addTrack(newFormat);
                */
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

                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    // The codec config data was pulled out and fed to the muxer when we got
                    // the INFO_OUTPUT_FORMAT_CHANGED status.  Ignore it.
                    if (VERBOSE) Log.d(TAG, "ignoring BUFFER_FLAG_CODEC_CONFIG");
                    mBufferInfo.size = 0;
                }

                if (mBufferInfo.size != 0) {
                    if (!mMuxerStarted) {
                        throw new RuntimeException("muxer hasn't started");
                    }

                    // adjust the ByteBuffer values to match BufferInfo (not needed?)
                    encodedData.position(mBufferInfo.offset);
                    encodedData.limit(mBufferInfo.offset + mBufferInfo.size);

                    mMuxer.writeSampleData((encoder == mVideoEncoder) ? mVideoTrackIndex : mAudioTrackIndex, encodedData, mBufferInfo);
                    if (VERBOSE) Log.d(TAG, "sent " + mBufferInfo.size + ((isVideoEncoder) ? " video":" audio") + " bytes to muxer with pts " + mBufferInfo.presentationTimeUs);
                }

                encoder.releaseOutputBuffer(encoderStatus, false);

                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    if (!endOfStream) {
                        Log.w(TAG, "reached end of stream unexpectedly");
                    } else {
                        if (VERBOSE) Log.d(TAG, "end of stream reached");
                    }
                    break;      // out of while
                }
            }
        }
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
                encoder._offerEncoder(video_data, audio_data, presentationTimeNs);
            }
        }

        private void shiftEncoder(){
        }

        private void finalizeEncoder(){
            encoder._stop();
        }

        @Override
        public void run() {
            if(is_initialized){
                switch(type){
                    case ENCODE_FRAME:
                        if(VERBOSE) Log.i(TAG, "encodeFrame");
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
            }
            else{
                Log.e(TAG, "run() called but EncoderTask not initialized");
            }

        }

    }
}