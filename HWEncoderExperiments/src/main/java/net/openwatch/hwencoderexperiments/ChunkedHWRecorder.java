/*
 * Copyright 2013 The Android Open Source Project
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

// Enormous thanks to Andrew McFadden for his MediaCodec examples!
// Adapted from http://bigflake.com/mediacodec/CameraToMpegTest.java.txt

package net.openwatch.hwencoderexperiments;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.media.*;
import android.opengl.*;
import android.os.Trace;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.List;

/**
 * Record video from the camera preview and encode it as an MP4 file.  Demonstrates the use
 * of MediaMuxer and MediaCodec with Camera input.  Does not record audio.
 * <p/>
 * Generally speaking, it's better to use MediaRecorder for this sort of thing.  This example
 * demonstrates one possible advantage: editing of video as it's being encoded.  A GLES 2.0
 * fragment shader is used to perform a silly color tweak every 15 frames.
 * <p/>
 * This uses various features first available in Android "Jellybean" 4.3 (API 18).  There is
 * no equivalent functionality in previous releases.  (You can send the Camera preview to a
 * byte buffer with a fully-specified format, but MediaCodec encoders want different input
 * formats on different devices, and this use case wasn't well exercised in CTS pre-4.3.)
 * <p/>
 * (This was derived from bits and pieces of CTS tests, and is packaged as such, but is not
 * currently part of CTS.)
 */
public class ChunkedHWRecorder {
    private static final String TAG = "CameraToMpegTest";
    private static final boolean VERBOSE = false;           // lots of logging
    private static final boolean TRACE = true; // systrace
    // where to put the output file (note: /sdcard requires WRITE_EXTERNAL_STORAGE permission)
    private static String OUTPUT_DIR = "/sdcard/HWEncodingExperiments/";
    // parameters for the encoder
    private static final String VIDEO_MIME_TYPE = "video/avc";    // H.264 Advanced Video Coding
    private static final String AUDIO_MIME_TYPE = "audio/mp4a-latm";    // H.264 Advanced Video Coding
    private static final int OUTPUT_FORMAT = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4;
    private static final int VIDEO_WIDTH = 640;
    private static final int VIDEO_HEIGHT = 480;
    private static final int FRAME_RATE = 30;               // 30fps
    private static final int IFRAME_INTERVAL = 5;           // 5 seconds between I-frames
    private static final long CHUNK_DURATION_SEC = 5;       // Duration of video chunks

    // Display Surface
    private GLSurfaceView displaySurface;
    // encoder / muxer state
    private MediaCodec mVideoEncoder;
    private MediaCodec mAudioEncoder;
    private CodecInputSurface mInputSurface;
    private MediaMuxerWrapper mMuxerWrapper;
    private MediaMuxerWrapper mMuxerWrapper2;
    private TrackInfo mVideoTrackInfo;
    private TrackInfo mAudioTrackInfo;
    // camera state
    private Camera mCamera;
    private SurfaceTextureManager mStManager;
    // allocate one of these up front so we don't need to do it every time
    private MediaCodec.BufferInfo mVideoBufferInfo;
    private MediaCodec.BufferInfo mAudioBufferInfo;
    // The following formats are fed to MediaCodec.configure
    private MediaFormat mVideoFormat;
    private MediaFormat mAudioFormat;
    // The following are returned when encoder OUTPUT_FORMAT_CHANGED signaled
    private MediaFormat mVideoOutputFormat;
    private MediaFormat mAudioOutputFormat;

    // recording state
    private int leadingChunk = 1;
    long startWhen;
    int frameCount = 0;
    boolean eosSentToAudioEncoder = false;
    boolean audioEosRequested = false;
    boolean eosSentToVideoEncoder = false;
    boolean fullStopReceived = false;
    boolean fullStopPerformed = false;

    // debug state
    int totalFrameCount = 0;
    long startTime;


    // Audio
    public static final int SAMPLE_RATE = 44100;
    public static final int SAMPLES_PER_FRAME = 1024; // AAC
    public static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    public static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private AudioRecord audioRecord;
    private long lastEncodedAudioTimeStamp = 0;

    // MediaRecorder
    boolean useMediaRecorder = false;
    MediaRecorderWrapper mMediaRecorderWrapper;

    Context c;


    class TrackInfo {
        int index = 0;
        MediaMuxerWrapper muxerWrapper;
    }

    class MediaMuxerWrapper {
        MediaMuxer muxer;
        final int TOTAL_NUM_TRACKS = 2;
        boolean started = false;
        int chunk;
        int numTracksAdded = 0;
        int numTracksFinished = 0;

        Object sync = new Object();

        public MediaMuxerWrapper(int format, int chunk){
            this.chunk = chunk;
            restart(format, chunk);
        }

        public int addTrack(MediaFormat format){
            numTracksAdded++;
            int trackIndex = muxer.addTrack(format);
            if(numTracksAdded == TOTAL_NUM_TRACKS){
                if (VERBOSE) Log.i(TAG, "All tracks added, starting " + ((this == mMuxerWrapper) ? "muxer1" : "muxer2") +"!");
                muxer.start();
                started = true;
            }
            return trackIndex;
        }

        public void finishTrack(){
            numTracksFinished++;
            if(numTracksFinished == TOTAL_NUM_TRACKS){
                if (VERBOSE) Log.i(TAG, "All tracks finished, stopping " + ((this == mMuxerWrapper) ? "muxer1" : "muxer2") + "!");
                stop();
            }

        }

        public boolean allTracksAdded(){
            return (numTracksAdded == TOTAL_NUM_TRACKS);
        }

        public boolean allTracksFinished(){
            return (numTracksFinished == TOTAL_NUM_TRACKS);
        }


        public void stop(){
            if(muxer != null){
                if(!allTracksFinished()) Log.e(TAG, "Stopping Muxer before all tracks added!");
                if(!started) Log.e(TAG, "Stopping Muxer before it was started");
                muxer.stop();
                muxer.release();
                muxer = null;
                started = false;
                chunk = 0;
                numTracksAdded = 0;
                numTracksFinished = 0;
            }
        }

        private String outputPathForChunk(int chunk){
            return OUTPUT_DIR + VIDEO_WIDTH + "x" + VIDEO_HEIGHT + "_" + chunk + ".mp4";
        }

        private void restart(int format, int chunk){
            stop();
            try {
                muxer = new MediaMuxer(outputPathForChunk(chunk), format);
            } catch (IOException e) {
                throw new RuntimeException("MediaMuxer creation failed", e);
            }
        }
    }

    public ChunkedHWRecorder(Context c){
        this.c = c;
    }

    public void setDisplaySurface(GLSurfaceView displaySurface){
        this.displaySurface = displaySurface;
    }

    public void setDisplayEGLContext(EGLContext context){
        mInputSurface.mEGLDisplayContext = context;
    }

    boolean firstFrameReady = false;
    boolean eosReceived = false;
    public void startRecording(String outputDir){
        if(outputDir != null)
            OUTPUT_DIR = outputDir;

        int encBitRate = 1000000;      // bps
        int framesPerChunk = (int) CHUNK_DURATION_SEC * FRAME_RATE;
        Log.d(TAG, VIDEO_MIME_TYPE + " output " + VIDEO_WIDTH + "x" + VIDEO_HEIGHT + " @" + encBitRate);

        try {
            if (TRACE) Trace.beginSection("prepare");
            prepareCamera(VIDEO_WIDTH, VIDEO_HEIGHT, Camera.CameraInfo.CAMERA_FACING_BACK);
            prepareEncoder(VIDEO_WIDTH, VIDEO_HEIGHT, encBitRate);
            mInputSurface.makeEncodeContextCurrent();
            prepareSurfaceTexture();
            setupAudioRecord();
            if (TRACE) Trace.endSection();


            File outputHq = FileUtils.createTempFileInRootAppStorage(c, "hq.mp4");
            if (TRACE) Trace.beginSection("startMediaRecorder");
            if (useMediaRecorder) mMediaRecorderWrapper = new MediaRecorderWrapper(c, outputHq.getAbsolutePath(), mCamera);
            startAudioRecord();
            if (useMediaRecorder) mMediaRecorderWrapper.startRecording();
            if (TRACE) Trace.endSection();
            startWhen = System.nanoTime();

            mCamera.startPreview();
            SurfaceTexture st = mStManager.getSurfaceTexture();
            eosReceived = false;

            while (!(fullStopReceived && eosSentToVideoEncoder)) {
                // Feed any pending encoder output into the muxer.
                // Chunk encoding
                eosReceived = ((frameCount % framesPerChunk) == 0 && frameCount != 0);
                if (eosReceived) Log.i(TAG, "Chunkpoint on frame " + frameCount);
                audioEosRequested = eosReceived;  // test
                synchronized (mVideoTrackInfo.muxerWrapper.sync){
                    if (TRACE) Trace.beginSection("drainVideo");
                    drainEncoder(mVideoEncoder, mVideoBufferInfo, mVideoTrackInfo, eosReceived || fullStopReceived);
                    if (TRACE) Trace.endSection();
                }
                if (fullStopReceived){
                    break;
                }
                frameCount++;
                totalFrameCount++;

                // Acquire a new frame of input, and render it to the Surface.  If we had a
                // GLSurfaceView we could switch EGL contexts and call drawImage() a second
                // time to render it on screen.  The texture can be shared between contexts by
                // passing the GLSurfaceView's EGLContext as eglCreateContext()'s share_context
                // argument.
                if (TRACE) Trace.beginSection("awaitImage");
                mStManager.awaitNewImage();
                if (TRACE) Trace.endSection();
                if (TRACE) Trace.beginSection("drawImage");
                mStManager.drawImage();
                if (TRACE) Trace.endSection();


                // Set the presentation time stamp from the SurfaceTexture's time stamp.  This
                // will be used by MediaMuxer to set the PTS in the video.
                mInputSurface.setPresentationTime(st.getTimestamp() - startWhen);

                // Submit it to the encoder.  The eglSwapBuffers call will block if the input
                // is full, which would be bad if it stayed full until we dequeued an output
                // buffer (which we can't do, since we're stuck here).  So long as we fully drain
                // the encoder before supplying additional input, the system guarantees that we
                // can supply another frame without blocking.
                if (VERBOSE) Log.d(TAG, "sending frame to encoder");
                if (TRACE) Trace.beginSection("swapBuffers");
                mInputSurface.swapBuffers();
                if (TRACE) Trace.endSection();
                if (!firstFrameReady) startTime = System.nanoTime();
                firstFrameReady = true;

                /*
                if (TRACE) Trace.beginSection("sendAudio");
                sendAudioToEncoder(false);
                if (TRACE) Trace.endSection();
                */
            }
            Log.i(TAG, "Exiting video encode loop");

        } catch (Exception e){
            Log.e(TAG, "Encoding loop exception!");
            e.printStackTrace();
        } finally {
        }
    }

    public void stopRecording(){
        Log.i(TAG, "stopRecording");
        fullStopReceived = true;
        if (useMediaRecorder) mMediaRecorderWrapper.stopRecording();
        double recordingDurationSec = (System.nanoTime() - startTime) / 1000000000.0;
        Log.i(TAG, "Recorded " + recordingDurationSec + " s. Expected " + (FRAME_RATE * recordingDurationSec) + " frames. Got " + totalFrameCount + " for " + (totalFrameCount / recordingDurationSec) + " fps");
    }

    /**
     * Called internally to finalize HQ and last chunk
     */
    public void _stopRecording(){
        fullStopPerformed = true;
        mMediaRecorderWrapper.stopRecording();
        releaseCamera();
        releaseEncodersAndMuxer();
        releaseSurfaceTexture();
        if (mInputSurface != null) {
            mInputSurface.release();
            mInputSurface = null;
        }
    }

    private void setupAudioRecord(){
        int min_buffer_size = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        int buffer_size = SAMPLES_PER_FRAME * 10;
        if (buffer_size < min_buffer_size)
            buffer_size = ((min_buffer_size / SAMPLES_PER_FRAME) + 1) * SAMPLES_PER_FRAME * 2;

        audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,       // source
                SAMPLE_RATE,                         // sample rate, hz
                CHANNEL_CONFIG,                      // channels
                AUDIO_FORMAT,                        // audio format
                buffer_size);                        // buffer size (bytes)
    }

    private void startAudioRecord(){
        if(audioRecord != null){

            new Thread(new Runnable(){

                @Override
                public void run() {
                    audioRecord.startRecording();
                    boolean audioEosRequestedCopy = false;
                    while(true){

                        if(!firstFrameReady)
                            continue;
                        audioEosRequestedCopy = audioEosRequested; // make sure audioEosRequested doesn't change value mid loop
                        if (audioEosRequestedCopy || fullStopReceived){ // TODO post eosReceived message with Handler?
                            Log.i(TAG, "Audio loop caught audioEosRequested / fullStopReceived " + audioEosRequestedCopy + " " + fullStopReceived);
                            if (TRACE) Trace.beginSection("sendAudio");
                            sendAudioToEncoder(true);
                            if (TRACE) Trace.endSection();
                        }
                        if (fullStopReceived){
                            Log.i(TAG, "Stopping AudioRecord");
                            audioRecord.stop();
                        }

                        synchronized (mAudioTrackInfo.muxerWrapper.sync){
                            if (TRACE) Trace.beginSection("drainAudio");
                            drainEncoder(mAudioEncoder, mAudioBufferInfo, mAudioTrackInfo, audioEosRequestedCopy || fullStopReceived);
                            if (TRACE) Trace.endSection();
                        }

                        if (audioEosRequestedCopy) audioEosRequested = false;

                        if (!fullStopReceived){
                            if (TRACE) Trace.beginSection("sendAudio");
                            sendAudioToEncoder(false);
                            if (TRACE) Trace.endSection();
                        }else{
                            break;
                        }
                    } // end while
                }
            }).start();

        }

    }

    public void sendAudioToEncoder(boolean endOfStream) {
        // send current frame data to encoder
        try {
            ByteBuffer[] inputBuffers = mAudioEncoder.getInputBuffers();
            int inputBufferIndex = mAudioEncoder.dequeueInputBuffer(-1);
            if (inputBufferIndex >= 0) {
                ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
                inputBuffer.clear();
                long presentationTimeNs = System.nanoTime();
                int inputLength =  audioRecord.read(inputBuffer, SAMPLES_PER_FRAME );
                presentationTimeNs -= (inputLength / SAMPLE_RATE ) / 1000000000;
                if(inputLength == AudioRecord.ERROR_INVALID_OPERATION)
                    Log.e(TAG, "Audio read error");

                //long presentationTimeUs = (presentationTimeNs - startWhen) / 1000;
                long presentationTimeUs = (presentationTimeNs - startWhen) / 1000;
                if (VERBOSE) Log.i(TAG, "queueing " + inputLength + " audio bytes with pts " + presentationTimeUs);
                if (endOfStream) {
                    Log.i(TAG, "EOS received in sendAudioToEncoder");
                    mAudioEncoder.queueInputBuffer(inputBufferIndex, 0, inputLength, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    eosSentToAudioEncoder = true;
                } else {
                    mAudioEncoder.queueInputBuffer(inputBufferIndex, 0, inputLength, presentationTimeUs, 0);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "_offerAudioEncoder exception");
            t.printStackTrace();
        }
    }


    /**
     * Attempts to find a preview size that matches the provided width and height (which
     * specify the dimensions of the encoded video).  If it fails to find a match it just
     * uses the default preview size.
     * <p/>
     * TODO: should do a best-fit match.
     */
    private static void choosePreviewSize(Camera.Parameters parms, int width, int height) {
        // We should make sure that the requested MPEG size is less than the preferred
        // size, and has the same aspect ratio.
        Camera.Size ppsfv = parms.getPreferredPreviewSizeForVideo();
        if (VERBOSE && ppsfv != null) {
            Log.d(TAG, "Camera preferred preview size for video is " +
                    ppsfv.width + "x" + ppsfv.height);
        }

        for (Camera.Size size : parms.getSupportedPreviewSizes()) {
            if (size.width == width && size.height == height) {
                parms.setPreviewSize(width, height);
                return;
            }
        }

        Log.w(TAG, "Unable to set preview size to " + width + "x" + height);
        if (ppsfv != null) {
            parms.setPreviewSize(ppsfv.width, ppsfv.height);
        }
    }

    /**
     * Configures Camera for video capture.  Sets mCamera.
     * <p/>
     * Opens a Camera and sets parameters.  Does not start preview.
     */
    private void prepareCamera(int encWidth, int encHeight, int cameraType) {
        if (cameraType != Camera.CameraInfo.CAMERA_FACING_FRONT && cameraType != Camera.CameraInfo.CAMERA_FACING_BACK) {
            throw new RuntimeException("Invalid cameraType");
        }

        Camera.CameraInfo info = new Camera.CameraInfo();

        // Try to find a front-facing camera (e.g. for videoconferencing).
        int numCameras = Camera.getNumberOfCameras();
        for (int i = 0; i < numCameras; i++) {
            Camera.getCameraInfo(i, info);
            if (info.facing == cameraType) {
                mCamera = Camera.open(i);
                break;
            }
        }
        if (mCamera == null && cameraType == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            Log.d(TAG, "No front-facing camera found; opening default");
            mCamera = Camera.open();    // opens first back-facing camera
        }
        if (mCamera == null) {
            throw new RuntimeException("Unable to open camera");
        }

        Camera.Parameters parms = mCamera.getParameters();
        List<int[]> fpsRanges = parms.getSupportedPreviewFpsRange();
        int[] maxFpsRange = fpsRanges.get(fpsRanges.size() - 1);
        parms.setPreviewFpsRange(maxFpsRange[0], maxFpsRange[1]);

        choosePreviewSize(parms, encWidth, encHeight);
        // leave the frame rate set to default
        mCamera.setParameters(parms);

        Camera.Size size = parms.getPreviewSize();
        Log.d(TAG, "Camera preview size is " + size.width + "x" + size.height);
    }

    /**
     * Stops camera preview, and releases the camera to the system.
     */
    private void releaseCamera() {
        if (VERBOSE) Log.d(TAG, "releasing camera");
        if (mCamera != null) {
            mCamera.stopPreview();
            mCamera.release();
            mCamera = null;
        }
    }

    /**
     * Configures SurfaceTexture for camera preview.  Initializes mStManager, and sets the
     * associated SurfaceTexture as the Camera's "preview texture".
     * <p/>
     * Configure the EGL surface that will be used for output before calling here.
     */
    private void prepareSurfaceTexture() {
        mStManager = new SurfaceTextureManager();
        SurfaceTexture st = mStManager.getSurfaceTexture();
        try {
            mCamera.setPreviewTexture(st);
        } catch (IOException ioe) {
            throw new RuntimeException("setPreviewTexture failed", ioe);
        }
    }

    /**
     * Releases the SurfaceTexture.
     */
    private void releaseSurfaceTexture() {
        if (mStManager != null) {
            mStManager.release();
            mStManager = null;
        }
    }

    /**
     * Configures encoder and muxer state, and prepares the input Surface.  Initializes
     * mVideoEncoder, mMuxerWrapper, mInputSurface, mVideoBufferInfo, mVideoTrackInfo, and mMuxerStarted.
     */
    private void prepareEncoder(int width, int height, int bitRate) {
        eosSentToAudioEncoder = false;
        eosSentToVideoEncoder = false;
        fullStopReceived = false;
        mVideoBufferInfo = new MediaCodec.BufferInfo();
        mVideoTrackInfo = new TrackInfo();

        mVideoFormat = MediaFormat.createVideoFormat(VIDEO_MIME_TYPE, width, height);

        // Set some properties.  Failing to specify some of these can cause the MediaCodec
        // configure() call to throw an unhelpful exception.
        mVideoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        mVideoFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        mVideoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        mVideoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);
        if (VERBOSE) Log.d(TAG, "format: " + mVideoFormat);

        // Create a MediaCodec encoder, and configure it with our format.  Get a Surface
        // we can use for input and wrap it with a class that handles the EGL work.
        //
        // If you want to have two EGL contexts -- one for display, one for recording --
        // you will likely want to defer instantiation of CodecInputSurface until after the
        // "display" EGL context is created, then modify the eglCreateContext call to
        // take eglGetCurrentContext() as the share_context argument.
        mVideoEncoder = MediaCodec.createEncoderByType(VIDEO_MIME_TYPE);
        mVideoEncoder.configure(mVideoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mInputSurface = new CodecInputSurface(mVideoEncoder.createInputSurface());
        mVideoEncoder.start();

        mAudioBufferInfo = new MediaCodec.BufferInfo();
        mAudioTrackInfo = new TrackInfo();

        mAudioFormat = new MediaFormat();
        mAudioFormat.setString(MediaFormat.KEY_MIME, AUDIO_MIME_TYPE);
        mAudioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        mAudioFormat.setInteger(MediaFormat.KEY_SAMPLE_RATE, 44100);
        mAudioFormat.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1);
        mAudioFormat.setInteger(MediaFormat.KEY_BIT_RATE, 128000);
        mAudioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);

        mAudioEncoder = MediaCodec.createEncoderByType(AUDIO_MIME_TYPE);
        mAudioEncoder.configure(mAudioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mAudioEncoder.start();

        // Output filename.  Ideally this would use Context.getFilesDir() rather than a
        // hard-coded output directory.
        String outputPath = OUTPUT_DIR + "chunktest." + width + "x" + height + String.valueOf(leadingChunk) + ".mp4";
        Log.i(TAG, "Output file is " + outputPath);


        // Create a MediaMuxer.  We can't add the video track and start() the muxer here,
        // because our MediaFormat doesn't have the Magic Goodies.  These can only be
        // obtained from the encoder after it has started processing data.
        //
        // We're not actually interested in multiplexing audio.  We just want to convert
        // the raw H.264 elementary stream we get from MediaCodec into a .mp4 file.
        //resetMediaMuxer(outputPath);
        mMuxerWrapper = new MediaMuxerWrapper(OUTPUT_FORMAT, leadingChunk);
        mMuxerWrapper2 = new MediaMuxerWrapper(OUTPUT_FORMAT, leadingChunk + 1); // prepared for next chunk


        mVideoTrackInfo.index = -1;
        mVideoTrackInfo.muxerWrapper = mMuxerWrapper;
        mAudioTrackInfo.index = -1;
        mAudioTrackInfo.muxerWrapper = mMuxerWrapper;
    }

    private void stopAndReleaseVideoEncoder(){
        eosSentToVideoEncoder = false;
        frameCount = 0;
        if (mVideoEncoder != null) {
            mVideoEncoder.stop();
            mVideoEncoder.release();
            mVideoEncoder = null;
        }
    }


    private void stopAndReleaseAudioEncoder(){
        lastEncodedAudioTimeStamp = 0;
        eosSentToAudioEncoder = false;

        if (mAudioEncoder != null) {
            mAudioEncoder.stop();
            mAudioEncoder.release();
            mAudioEncoder = null;
        }
    }

    private void stopAndReleaseEncoders(){
        stopAndReleaseVideoEncoder();
        stopAndReleaseAudioEncoder();
    }

    /**
     * This can be called within drainEncoder, when the end of stream is reached
     */
    private void chunkVideoEncoder(){
        stopAndReleaseVideoEncoder();
        // Start Encoder
        mVideoBufferInfo = new MediaCodec.BufferInfo();
        //mVideoTrackInfo = new TrackInfo();
        advanceVideoMediaMuxer();
        mVideoEncoder = MediaCodec.createEncoderByType(VIDEO_MIME_TYPE);
        mVideoEncoder.configure(mVideoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mInputSurface.updateSurface(mVideoEncoder.createInputSurface());
        mVideoEncoder.start();
        mInputSurface.makeEncodeContextCurrent();
    }

    private void advanceVideoMediaMuxer(){
        MediaMuxerWrapper videoMuxer = (mVideoTrackInfo.muxerWrapper == mMuxerWrapper) ? mMuxerWrapper : mMuxerWrapper2;
        MediaMuxerWrapper audioMuxer = (mAudioTrackInfo.muxerWrapper == mMuxerWrapper) ? mMuxerWrapper : mMuxerWrapper2;
        Log.i("advanceVideo", "video on " + ((mVideoTrackInfo.muxerWrapper == mMuxerWrapper) ? "muxer1" : "muxer2"));
        if(videoMuxer == audioMuxer){
            // if both encoders are on same muxer, switch to other muxer
            leadingChunk++;
            if(videoMuxer == mMuxerWrapper){
                Log.i("advanceVideo", "encoders on same muxer. swapping.");
                mVideoTrackInfo.muxerWrapper = mMuxerWrapper2;
                // testing: can we start next muxer immediately given MediaCodec.getOutputFormat() values?

            }else if(videoMuxer == mMuxerWrapper2){
                Log.i("advanceVideo", "encoders on same muxer. swapping.");
                mVideoTrackInfo.muxerWrapper = mMuxerWrapper;
                // testing: can we start next muxer immediately given MediaCodec.getOutputFormat() values?
            }
            if(mVideoOutputFormat != null && mAudioOutputFormat != null){
                mVideoTrackInfo.muxerWrapper.addTrack(mVideoOutputFormat);
                mVideoTrackInfo.muxerWrapper.addTrack(mAudioOutputFormat);
            }else{
                Log.e(TAG, "mVideoOutputFormat or mAudioOutputFormat is null!");
            }
        }else{
            // if encoders are separate, finalize this muxer, and switch to others
            Log.i("advanceVideo", "encoders on diff muxers. restarting");
            mVideoTrackInfo.muxerWrapper.restart(OUTPUT_FORMAT, leadingChunk + 1); // prepare muxer for next chunk, but don't alter leadingChunk
            mVideoTrackInfo.muxerWrapper = mAudioTrackInfo.muxerWrapper;
        }
    }

    /**
     * This can be called within drainEncoder, when the end of stream is reached
     */
    private void chunkAudioEncoder(){
        stopAndReleaseAudioEncoder();

        // Start Encoder
        mAudioBufferInfo = new MediaCodec.BufferInfo();
        //mVideoTrackInfo = new TrackInfo();
        advanceAudioMediaMuxer();
        mAudioEncoder = MediaCodec.createEncoderByType(AUDIO_MIME_TYPE);
        mAudioEncoder.configure(mAudioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mAudioEncoder.start();
    }

    private void advanceAudioMediaMuxer(){
        MediaMuxerWrapper videoMuxer = (mVideoTrackInfo.muxerWrapper == mMuxerWrapper) ? mMuxerWrapper : mMuxerWrapper2;
        MediaMuxerWrapper audioMuxer = (mAudioTrackInfo.muxerWrapper == mMuxerWrapper) ? mMuxerWrapper : mMuxerWrapper2;
        Log.i("advanceAudio", "audio on " + ((mAudioTrackInfo.muxerWrapper == mMuxerWrapper) ? "muxer1" : "muxer2"));
        if(videoMuxer == audioMuxer){
            // If both encoders are on same muxer, switch to other muxer
            Log.i("advanceAudio", "encoders on same muxer. swapping.");
            leadingChunk++;
            if(videoMuxer == mMuxerWrapper){
                mAudioTrackInfo.muxerWrapper = mMuxerWrapper2;
            }else if(videoMuxer == mMuxerWrapper2){
                mAudioTrackInfo.muxerWrapper = mMuxerWrapper;
            }
            if(mVideoOutputFormat != null && mAudioOutputFormat != null){
                mAudioTrackInfo.muxerWrapper.addTrack(mVideoOutputFormat);
                mAudioTrackInfo.muxerWrapper.addTrack(mAudioOutputFormat);
            }else{
                Log.e(TAG, "mVideoOutputFormat or mAudioOutputFormat is null!");
            }
        }else{
            // if encoders are separate, finalize this muxer, and switch to others
            Log.i("advanceAudio", "encoders on diff muxers. restarting");
            mAudioTrackInfo.muxerWrapper.restart(OUTPUT_FORMAT, leadingChunk + 1); // prepare muxer for next chunk, but don't alter leadingChunk
            mAudioTrackInfo.muxerWrapper = mVideoTrackInfo.muxerWrapper;
        }
    }

    /**
     * Releases encoder resources.
     */
    private void releaseEncodersAndMuxer() {
        if (VERBOSE) Log.d(TAG, "releasing encoder objects");
        stopAndReleaseEncoders();
        if (mMuxerWrapper != null) {
            synchronized (mMuxerWrapper.sync){
                mMuxerWrapper.stop();
                mMuxerWrapper = null;
            }
        }
        if (mMuxerWrapper2 != null) {
            synchronized (mMuxerWrapper2.sync){
                mMuxerWrapper2.stop();
                mMuxerWrapper2 = null;
            }
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
    private void drainEncoder(MediaCodec encoder, MediaCodec.BufferInfo bufferInfo, TrackInfo trackInfo, boolean endOfStream) {
        final int TIMEOUT_USEC = 100;

        //TODO: Get Muxer from trackInfo
        MediaMuxerWrapper muxerWrapper = trackInfo.muxerWrapper;

        if (VERBOSE) Log.d(TAG, "drain" + ((encoder == mVideoEncoder) ? "Video" : "Audio") + "Encoder(" + endOfStream + ")");
        if (endOfStream && encoder == mVideoEncoder) {
            if (VERBOSE) Log.d(TAG, "sending EOS to " + ((encoder == mVideoEncoder) ? "video" : "audio") + " encoder");
            encoder.signalEndOfInputStream();
            eosSentToVideoEncoder = true;
        }
        //testing
        ByteBuffer[] encoderOutputBuffers = encoder.getOutputBuffers();

        while (true) {
            int encoderStatus = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC);
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // no output available yet
                if (!endOfStream) {
                    if (VERBOSE) Log.d(TAG, "no output available. aborting drain");
                    break;      // out of while
                } else {
                    if (VERBOSE) Log.d(TAG, "no output available, spinning to await EOS");
                }
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                // not expected for an encoder
                encoderOutputBuffers = encoder.getOutputBuffers();
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // should happen before receiving buffers, and should only happen once

                if (muxerWrapper.started) {
                    //Log.e(TAG, "format changed after muxer start! Can we ignore?");
                    //throw new RuntimeException("format changed after muxer start");
                }else{
                    MediaFormat newFormat = encoder.getOutputFormat();
                    if(encoder == mVideoEncoder)
                        mVideoOutputFormat = newFormat;
                    else if(encoder == mAudioEncoder)
                        mAudioOutputFormat = newFormat;

                    // now that we have the Magic Goodies, start the muxer
                    trackInfo.index = muxerWrapper.addTrack(newFormat);
                    if(!muxerWrapper.allTracksAdded())
                        break;  // Allow both encoders to send output format changed before attempting to write samples
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
                    if (!trackInfo.muxerWrapper.started) {
                        Log.e(TAG, "Muxer not started. dropping " + ((encoder == mVideoEncoder) ? " video" : " audio") + " frames");
                        //throw new RuntimeException("muxer hasn't started");
                    } else{
                        // adjust the ByteBuffer values to match BufferInfo (not needed?)
                        encodedData.position(bufferInfo.offset);
                        encodedData.limit(bufferInfo.offset + bufferInfo.size);
                        if(encoder == mAudioEncoder){
                            if(bufferInfo.presentationTimeUs < lastEncodedAudioTimeStamp)
                                bufferInfo.presentationTimeUs = lastEncodedAudioTimeStamp += 23219; // Magical AAC encoded frame time
                            lastEncodedAudioTimeStamp = bufferInfo.presentationTimeUs;
                        }
                        if(bufferInfo.presentationTimeUs < 0){
                            bufferInfo.presentationTimeUs = 0;
                        }
                        muxerWrapper.muxer.writeSampleData(trackInfo.index, encodedData, bufferInfo);

                        if (VERBOSE)
                            Log.d(TAG, "sent " + bufferInfo.size + ((encoder == mVideoEncoder) ? " video" : " audio") + " bytes to muxer with pts " + bufferInfo.presentationTimeUs);

                    }
                }

                encoder.releaseOutputBuffer(encoderStatus, false);

                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    if (!endOfStream) {
                        Log.w(TAG, "reached end of stream unexpectedly");
                    } else {
                        muxerWrapper.finishTrack();
                        if (VERBOSE) Log.d(TAG, "end of " + ((encoder == mVideoEncoder) ? " video" : " audio") + " stream reached. ");
                        if(!fullStopReceived){
                            if(encoder == mVideoEncoder){
                                Log.i(TAG, "Chunking video encoder");
                                if (TRACE) Trace.beginSection("chunkVideoEncoder");
                                chunkVideoEncoder();
                                if (TRACE) Trace.endSection();
                            }else if(encoder == mAudioEncoder){
                                Log.i(TAG, "Chunking audio encoder");
                                if (TRACE) Trace.beginSection("chunkAudioEncoder");
                                chunkAudioEncoder();
                                if (TRACE) Trace.endSection();
                            }else
                                Log.e(TAG, "Unknown encoder passed to drainEncoder!");
                        }else{

                            if(encoder == mVideoEncoder){
                                Log.i(TAG, "Stopping and releasing video encoder");
                                stopAndReleaseVideoEncoder();
                            } else if(encoder == mAudioEncoder){
                                Log.i(TAG, "Stopping and releasing audio encoder");
                                stopAndReleaseAudioEncoder();
                            }
                            //stopAndReleaseEncoders();
                        }
                    }
                    break;      // out of while
                }
            }
        }
        long endTime = System.nanoTime();
    }


    /**
     * Holds state associated with a Surface used for MediaCodec encoder input.
     * <p/>
     * The constructor takes a Surface obtained from MediaCodec.createInputSurface(), and uses
     * that to create an EGL window surface.  Calls to eglSwapBuffers() cause a frame of data to
     * be sent to the video encoder.
     * <p/>
     * This object owns the Surface -- releasing this will release the Surface too.
     */
    private static class CodecInputSurface {
        private static final int EGL_RECORDABLE_ANDROID = 0x3142;
        private EGLDisplay mEGLDisplay = EGL14.EGL_NO_DISPLAY;
        private EGLContext mEGLEncodeContext = EGL14.EGL_NO_CONTEXT;
        public static EGLContext mEGLDisplayContext = EGL14.EGL_NO_CONTEXT;
        private EGLSurface mEGLSurface = EGL14.EGL_NO_SURFACE;
        private Surface mSurface;

        EGLConfig[] configs;
        int[] surfaceAttribs = {
                EGL14.EGL_NONE
        };

        /**
         * Creates a CodecInputSurface from a Surface.
         */
        public CodecInputSurface(Surface surface) {
            if (surface == null) {
                throw new NullPointerException();
            }
            mSurface = surface;

            eglSetup();
        }

        public void updateSurface(Surface newSurface){
            // Destroy old EglSurface
            EGL14.eglDestroySurface(mEGLDisplay, mEGLSurface);
            mSurface = newSurface;
            // create new EglSurface
            mEGLSurface = EGL14.eglCreateWindowSurface(mEGLDisplay, configs[0], mSurface,
                    surfaceAttribs, 0);
            checkEglError("eglCreateWindowSurface");
            // eglMakeCurrent called in chunkRecording() after mVideoEncoder.start()
        }

        /**
         * Prepares EGL.  We want a GLES 2.0 context and a surface that supports recording.
         */
        private void eglSetup() {
            if(VERBOSE) Log.i(TAG, "Creating EGL14 Surface");
            mEGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (mEGLDisplay == EGL14.EGL_NO_DISPLAY) {
                throw new RuntimeException("unable to get EGL14 display");
            }
            int[] version = new int[2];
            if (!EGL14.eglInitialize(mEGLDisplay, version, 0, version, 1)) {
                throw new RuntimeException("unable to initialize EGL14");
            }

            // Configure EGL for recording and OpenGL ES 2.0.
            int[] attribList = {
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL_RECORDABLE_ANDROID, 1,
                    EGL14.EGL_NONE
            };
            configs = new EGLConfig[1];
            int[] numConfigs = new int[1];
            EGL14.eglChooseConfig(mEGLDisplay, attribList, 0, configs, 0, configs.length,
                    numConfigs, 0);
            checkEglError("eglCreateContext RGB888+recordable ES2");

            // Configure context for OpenGL ES 2.0.
            int[] attrib_list = {
                    EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                    EGL14.EGL_NONE
            };
            if(mEGLDisplayContext == EGL14.EGL_NO_CONTEXT) Log.e(TAG, "mEGLDisplayContext not set properly");
            mEGLEncodeContext = EGL14.eglCreateContext(mEGLDisplay, configs[0], EGL14.eglGetCurrentContext(),
                    attrib_list, 0);
            checkEglError("eglCreateContext");

            // Create a window surface, and attach it to the Surface we received.
            mEGLSurface = EGL14.eglCreateWindowSurface(mEGLDisplay, configs[0], mSurface,
                    surfaceAttribs, 0);
            checkEglError("eglCreateWindowSurface");
        }

        /**
         * Discards all resources held by this class, notably the EGL context.  Also releases the
         * Surface that was passed to our constructor.
         */
        public void release() {
            if (mEGLDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(mEGLDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT);
                EGL14.eglDestroySurface(mEGLDisplay, mEGLSurface);
                EGL14.eglDestroyContext(mEGLDisplay, mEGLEncodeContext);
                EGL14.eglReleaseThread();
                EGL14.eglTerminate(mEGLDisplay);
            }
            mSurface.release();

            mEGLDisplay = EGL14.EGL_NO_DISPLAY;
            mEGLEncodeContext = EGL14.EGL_NO_CONTEXT;
            mEGLSurface = EGL14.EGL_NO_SURFACE;

            mSurface = null;
         }

        public void makeDisplayContextCurrent(){
            makeCurrent(mEGLDisplayContext);
        }
        public void makeEncodeContextCurrent(){
            makeCurrent(mEGLEncodeContext);
        }

         /**
         * Makes our EGL context and surface current.
         */
        private void makeCurrent(EGLContext context) {
            EGL14.eglMakeCurrent(mEGLDisplay, mEGLSurface, mEGLSurface, context);
            checkEglError("eglMakeCurrent");
        }

        /**
         * Calls eglSwapBuffers.  Use this to "publish" the current frame.
         */
        public boolean swapBuffers() {
            boolean result = EGL14.eglSwapBuffers(mEGLDisplay, mEGLSurface);
            checkEglError("eglSwapBuffers");
            return result;
        }

        /**
         * Sends the presentation time stamp to EGL.  Time is expressed in nanoseconds.
         */
        public void setPresentationTime(long nsecs) {
            EGLExt.eglPresentationTimeANDROID(mEGLDisplay, mEGLSurface, nsecs);
            checkEglError("eglPresentationTimeANDROID");
        }

        /**
         * Checks for EGL errors.  Throws an exception if one is found.
         */
        private void checkEglError(String msg) {
            int error;
            if ((error = EGL14.eglGetError()) != EGL14.EGL_SUCCESS) {
                throw new RuntimeException(msg + ": EGL error: 0x" + Integer.toHexString(error));
            }
        }
    }

    /**
     * Manages a SurfaceTexture.  Creates SurfaceTexture and TextureRender objects, and provides
     * functions that wait for frames and render them to the current EGL surface.
     * <p/>
     * The SurfaceTexture can be passed to Camera.setPreviewTexture() to receive camera output.
     */
    private static class SurfaceTextureManager
            implements SurfaceTexture.OnFrameAvailableListener {
        private SurfaceTexture mSurfaceTexture;
        private ChunkedHWRecorder.STextureRender mTextureRender;
        private Object mFrameSyncObject = new Object();     // guards mFrameAvailable
        private boolean mFrameAvailable;

        /**
         * Creates instances of TextureRender and SurfaceTexture.
         */
        public SurfaceTextureManager() {
            mTextureRender = new ChunkedHWRecorder.STextureRender();
            mTextureRender.surfaceCreated();

            if (VERBOSE) Log.d(TAG, String.format("textureID=%d", mTextureRender.getTextureId()) );
            mSurfaceTexture = new SurfaceTexture(mTextureRender.getTextureId());

            // This doesn't work if this object is created on the thread that CTS started for
            // these test cases.
            //
            // The CTS-created thread has a Looper, and the SurfaceTexture constructor will
            // create a Handler that uses it.  The "frame available" message is delivered
            // there, but since we're not a Looper-based thread we'll never see it.  For
            // this to do anything useful, OutputSurface must be created on a thread without
            // a Looper, so that SurfaceTexture uses the main application Looper instead.
            //
            // Java language note: passing "this" out of a constructor is generally unwise,
            // but we should be able to get away with it here.
            mSurfaceTexture.setOnFrameAvailableListener(this);
        }

        public void release() {
            // this causes a bunch of warnings that appear harmless but might confuse someone:
            //  W BufferQueue: [unnamed-3997-2] cancelBuffer: BufferQueue has been abandoned!
            //mSurfaceTexture.release();

            mTextureRender = null;
            mSurfaceTexture = null;
        }

        /**
         * Returns the SurfaceTexture.
         */
        public SurfaceTexture getSurfaceTexture() {
            return mSurfaceTexture;
        }

        /**
         * Replaces the fragment shader.
         */
        public void changeFragmentShader(String fragmentShader) {
            mTextureRender.changeFragmentShader(fragmentShader);
        }

        /**
         * Latches the next buffer into the texture.  Must be called from the thread that created
         * the OutputSurface object.
         */
        public void awaitNewImage() {
            final int TIMEOUT_MS = 4500;
            synchronized (mFrameSyncObject) {
                while (!mFrameAvailable) {
                    try {
                        // Wait for onFrameAvailable() to signal us.  Use a timeout to avoid
                        // stalling the test if it doesn't arrive.
                        if(VERBOSE) Log.i(TAG, "Waiting for Frame in Thread");
                        mFrameSyncObject.wait(TIMEOUT_MS);
                        if (!mFrameAvailable) {
                            // TODO: if "spurious wakeup", continue while loop
                            throw new RuntimeException("Camera frame wait timed out");
                        }
                    } catch (InterruptedException ie) {
                        // shouldn't happen
                        throw new RuntimeException(ie);
                    }
                }
                mFrameAvailable = false;
            }

            // Latch the data.
            mTextureRender.checkGlError("before updateTexImage");
            mSurfaceTexture.updateTexImage();

        }

        /**
         * Draws the data from SurfaceTexture onto the current EGL surface.
         */
        public void drawImage() {
            mTextureRender.drawFrame(mSurfaceTexture);
        }

        @Override
        public void onFrameAvailable(SurfaceTexture st) {
            if (VERBOSE) Log.d(TAG, "new frame available");
            synchronized (mFrameSyncObject) {
                if (mFrameAvailable) {
                    throw new RuntimeException("mFrameAvailable already set, frame could be dropped");
                }
                mFrameAvailable = true;
                mFrameSyncObject.notifyAll();
            }
        }
    }

    /**
     * Code for rendering a texture onto a surface using OpenGL ES 2.0.
     */
    private static class STextureRender {
        private static final int FLOAT_SIZE_BYTES = 4;
        private static final int TRIANGLE_VERTICES_DATA_STRIDE_BYTES = 5 * FLOAT_SIZE_BYTES;
        private static final int TRIANGLE_VERTICES_DATA_POS_OFFSET = 0;
        private static final int TRIANGLE_VERTICES_DATA_UV_OFFSET = 3;
        private static final String VERTEX_SHADER =
                "uniform mat4 uMVPMatrix;\n" +
                        "uniform mat4 uSTMatrix;\n" +
                        "attribute vec4 aPosition;\n" +
                        "attribute vec4 aTextureCoord;\n" +
                        "varying vec2 vTextureCoord;\n" +
                        "void main() {\n" +
                        "  gl_Position = uMVPMatrix * aPosition;\n" +
                        "  vTextureCoord = (uSTMatrix * aTextureCoord).xy;\n" +
                        "}\n";
        private static final String FRAGMENT_SHADER =
                "#extension GL_OES_EGL_image_external : require\n" +
                        "precision mediump float;\n" +      // highp here doesn't seem to matter
                        "varying vec2 vTextureCoord;\n" +
                        "uniform samplerExternalOES sTexture;\n" +
                        "void main() {\n" +
                        "  gl_FragColor = texture2D(sTexture, vTextureCoord);\n" +
                        "}\n";
        private final float[] mTriangleVerticesData = {
                // X, Y, Z, U, V
                -1.0f, -1.0f, 0, 0.f, 0.f,
                1.0f, -1.0f, 0, 1.f, 0.f,
                -1.0f, 1.0f, 0, 0.f, 1.f,
                1.0f, 1.0f, 0, 1.f, 1.f,
        };
        private FloatBuffer mTriangleVertices;
        private float[] mMVPMatrix = new float[16];
        private float[] mSTMatrix = new float[16];
        private int mProgram;
        private int mTextureID = -12345;
        private int muMVPMatrixHandle;
        private int muSTMatrixHandle;
        private int maPositionHandle;
        private int maTextureHandle;

        public STextureRender() {
            mTriangleVertices = ByteBuffer.allocateDirect(
                    mTriangleVerticesData.length * FLOAT_SIZE_BYTES)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            mTriangleVertices.put(mTriangleVerticesData).position(0);

            Matrix.setIdentityM(mSTMatrix, 0);
        }

        public int getTextureId() {
            return mTextureID;
        }

        public void drawFrame(SurfaceTexture st) {
            checkGlError("onDrawFrame start");
            st.getTransformMatrix(mSTMatrix);

            GLES20.glClearColor(0.0f, 1.0f, 0.0f, 1.0f);
            GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT | GLES20.GL_COLOR_BUFFER_BIT);

            GLES20.glUseProgram(mProgram);
            checkGlError("glUseProgram");

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTextureID);

            mTriangleVertices.position(TRIANGLE_VERTICES_DATA_POS_OFFSET);
            GLES20.glVertexAttribPointer(maPositionHandle, 3, GLES20.GL_FLOAT, false,
                    TRIANGLE_VERTICES_DATA_STRIDE_BYTES, mTriangleVertices);
            checkGlError("glVertexAttribPointer maPosition");
            GLES20.glEnableVertexAttribArray(maPositionHandle);
            checkGlError("glEnableVertexAttribArray maPositionHandle");

            mTriangleVertices.position(TRIANGLE_VERTICES_DATA_UV_OFFSET);
            GLES20.glVertexAttribPointer(maTextureHandle, 2, GLES20.GL_FLOAT, false,
                    TRIANGLE_VERTICES_DATA_STRIDE_BYTES, mTriangleVertices);
            checkGlError("glVertexAttribPointer maTextureHandle");
            GLES20.glEnableVertexAttribArray(maTextureHandle);
            checkGlError("glEnableVertexAttribArray maTextureHandle");

            Matrix.setIdentityM(mMVPMatrix, 0);
            GLES20.glUniformMatrix4fv(muMVPMatrixHandle, 1, false, mMVPMatrix, 0);
            GLES20.glUniformMatrix4fv(muSTMatrixHandle, 1, false, mSTMatrix, 0);

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            checkGlError("glDrawArrays");
            GLES20.glFinish();
        }

        /**
         * Initializes GL state.  Call this after the EGL surface has been created and made current.
         */
        public void surfaceCreated() {
            mProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
            if (mProgram == 0) {
                throw new RuntimeException("failed creating program");
            }
            maPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition");
            checkGlError("glGetAttribLocation aPosition");
            if (maPositionHandle == -1) {
                throw new RuntimeException("Could not get attrib location for aPosition");
            }
            maTextureHandle = GLES20.glGetAttribLocation(mProgram, "aTextureCoord");
            checkGlError("glGetAttribLocation aTextureCoord");
            if (maTextureHandle == -1) {
                throw new RuntimeException("Could not get attrib location for aTextureCoord");
            }

            muMVPMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix");
            checkGlError("glGetUniformLocation uMVPMatrix");
            if (muMVPMatrixHandle == -1) {
                throw new RuntimeException("Could not get attrib location for uMVPMatrix");
            }

            muSTMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uSTMatrix");
            checkGlError("glGetUniformLocation uSTMatrix");
            if (muSTMatrixHandle == -1) {
                throw new RuntimeException("Could not get attrib location for uSTMatrix");
            }

            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);

            mTextureID = textures[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTextureID);
            checkGlError("glBindTexture mTextureID");

            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER,
                    GLES20.GL_NEAREST);
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER,
                    GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S,
                    GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T,
                    GLES20.GL_CLAMP_TO_EDGE);
            checkGlError("glTexParameter");
        }

        /**
         * Replaces the fragment shader.  Pass in null to resetWithChunk to default.
         */
        public void changeFragmentShader(String fragmentShader) {
            if (fragmentShader == null) {
                fragmentShader = FRAGMENT_SHADER;
            }
            GLES20.glDeleteProgram(mProgram);
            mProgram = createProgram(VERTEX_SHADER, fragmentShader);
            if (mProgram == 0) {
                throw new RuntimeException("failed creating program");
            }
        }

        private int loadShader(int shaderType, String source) {
            int shader = GLES20.glCreateShader(shaderType);
            checkGlError("glCreateShader type=" + shaderType);
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            int[] compiled = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
            if (compiled[0] == 0) {
                Log.e(TAG, "Could not compile shader " + shaderType + ":");
                Log.e(TAG, " " + GLES20.glGetShaderInfoLog(shader));
                GLES20.glDeleteShader(shader);
                shader = 0;
            }
            return shader;
        }

        private int createProgram(String vertexSource, String fragmentSource) {
            int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource);
            if (vertexShader == 0) {
                return 0;
            }
            int pixelShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
            if (pixelShader == 0) {
                return 0;
            }

            int program = GLES20.glCreateProgram();
            checkGlError("glCreateProgram");
            if (program == 0) {
                Log.e(TAG, "Could not create program");
            }
            GLES20.glAttachShader(program, vertexShader);
            checkGlError("glAttachShader");
            GLES20.glAttachShader(program, pixelShader);
            checkGlError("glAttachShader");
            GLES20.glLinkProgram(program);
            int[] linkStatus = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
            if (linkStatus[0] != GLES20.GL_TRUE) {
                Log.e(TAG, "Could not link program: ");
                Log.e(TAG, GLES20.glGetProgramInfoLog(program));
                GLES20.glDeleteProgram(program);
                program = 0;
            }
            return program;
        }

        public void checkGlError(String op) {
            int error;
            while ((error = GLES20.glGetError()) != GLES20.GL_NO_ERROR) {
                Log.e(TAG, op + ": glError " + error);
                throw new RuntimeException(op + ": glError " + error);
            }
        }
    }
}