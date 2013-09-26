package net.openwatch.hwencoderexperiments;

import android.app.Activity;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;

import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.List;

public class HWRecorderActivity extends Activity implements TextureView.SurfaceTextureListener, SurfaceHolder.Callback {
    private static final String TAG = "CameraToMpegTest";

    public Camera mCamera;
    ChunkedAvcEncoder mEncoder;
    MediaRecorderWrapper mMediaRecorderWrapper;
    boolean recording = false;
    int bufferSize = 460800; // 640x480
    //int bufferSize = 1382400; // 720p
    int numFramesPreviewed = 0;
    AudioSoftwarePoller audioPoller;

    // testing
    long recordingStartTime = 0;
    long recordingEndTime = 0;

    boolean useTextureView = true;

    protected void onCreate (Bundle savedInstanceState){
        super.onCreate(savedInstanceState);
        if(useTextureView){
            setContentView(R.layout.activity_hwrecorder_textureview);
            TextureView tv = (TextureView) findViewById(R.id.cameraPreview);
            tv.setSurfaceTextureListener(this);
        }else{
            setContentView(R.layout.activity_hwrecorder_surfaceview);
            SurfaceView surfaceView = (SurfaceView) findViewById(R.id.cameraPreview);
            surfaceView.getHolder().addCallback(this);
        }

        // testing
        /*
        for(int i = MediaCodecList.getCodecCount() - 1; i >= 0; i--){
            MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);
            if(codecInfo.isEncoder()){
                for(String t : codecInfo.getSupportedTypes()){
                    try{
                        Log.i("CodecCapability", t);
                        Log.i("CodecCapability", codecInfo.getCapabilitiesForType(t).toString());
                    } catch(IllegalArgumentException e){
                        e.printStackTrace();
                    }
                }
            }
        }
        */
    }

    //static byte[] audioData;

    public void onRecordButtonClick(View v){
        recording = !recording;

        Log.i(TAG, "Record button hit. Start: " + String.valueOf(recording));

        if(recording){
            recordingStartTime = new Date().getTime();

            startMediaRecorder();
            mEncoder = new ChunkedAvcEncoder(getApplicationContext());
            mCamera.addCallbackBuffer(new byte[bufferSize]);
            mCamera.addCallbackBuffer(new byte[bufferSize]);
            mCamera.addCallbackBuffer(new byte[bufferSize]);
            mCamera.addCallbackBuffer(new byte[bufferSize]);
            mCamera.addCallbackBuffer(new byte[bufferSize]);
            mCamera.addCallbackBuffer(new byte[bufferSize]);
            mCamera.addCallbackBuffer(new byte[bufferSize]);
            mCamera.addCallbackBuffer(new byte[bufferSize]);
            mCamera.setPreviewCallbackWithBuffer(new Camera.PreviewCallback() {
                @Override
                public void onPreviewFrame(byte[] data, Camera camera) {
                    if(!audioPoller.is_recording){
                        mCamera.addCallbackBuffer(data);
                        return;
                    }
                    numFramesPreviewed++;
                    //Log.i(TAG, "Inter-frame time: " + (System.currentTimeMillis() - lastFrameTime) + " ms");
                    mEncoder.offerVideoEncoder(data);
                    //mCamera.addCallbackBuffer(data);
                    if(!recording){ // One frame must be sent with EOS flag after stop requested
                        camera.setPreviewCallbackWithBuffer(null);
                        audioPoller.stopPolling();
                        recordingEndTime = new Date().getTime();
                        Log.i(TAG, "HWRecorderActivity saw #frames: " + numFramesPreviewed + " over " +  ((recordingEndTime - recordingStartTime) / 1000) + " s for " + (numFramesPreviewed / ((recordingEndTime - recordingStartTime) / 1000)) + " fps");
                    }
                }
            });
            audioPoller = new AudioSoftwarePoller();
            audioPoller.setChunkedAvcEncoder(mEncoder);
            mEncoder.setAudioSoftwarePoller(audioPoller);
            mEncoder.setCameraActivity(this);
            audioPoller.startPolling();
        }else{
            stopMediaRecorder();
            if(mEncoder != null){
                mEncoder.stop();
            }
        }

    }

    static byte[] audioData;
    private static byte[] getSimulatedAudioInput(){
        int magnitude = 10;
        if(audioData == null){
            //audioData = new byte[1024];
            audioData = new byte[1470]; // this is roughly equal to the audio expected between 30 fps frames
            for(int x=0; x<audioData.length - 1; x++){
                audioData[x] = (byte) (magnitude * Math.sin(x));
            }
            Log.i(TAG, "generated simulated audio data");
        }
        return audioData;

    }

    private void setupCamera(){
        Camera.Parameters parameters = mCamera.getParameters();
        //List <Camera.Size> previewSizes = parameters.getSupportedPreviewSizes();
        parameters.setPreviewSize(640, 480);
        List<int[]> fpsRanges = parameters.getSupportedPreviewFpsRange();
        int[] maxFpsRange = fpsRanges.get(fpsRanges.size() - 1);
        parameters.setPreviewFpsRange(maxFpsRange[0], maxFpsRange[1]);
        parameters.setPreviewFormat(ImageFormat.YV12);
        mCamera.setParameters(parameters);
        mCamera.setDisplayOrientation(90);
        mCamera.startPreview();

    }

    private void setupCameraWithSurfaceTexture(SurfaceTexture surface){
        mCamera = Camera.open();
        try {
            mCamera.setPreviewTexture(surface);
            setupCamera();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void setupCameraWithSurfaceHolder(SurfaceHolder surfaceHolder){
        mCamera = Camera.open();
        try {
            mCamera.setPreviewDisplay(surfaceHolder);
            setupCamera();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void stopCamera(){
        mCamera.stopPreview();
        mCamera.release();
    }

    private void startMediaRecorder(){
        if(mMediaRecorderWrapper == null || !mMediaRecorderWrapper.isRecording){
            File outputHq = FileUtils.createTempFileInRootAppStorage(getApplicationContext(), "hq.mp4");
            mMediaRecorderWrapper = new MediaRecorderWrapper(getApplicationContext(), outputHq.getAbsolutePath(), mCamera);
            mMediaRecorderWrapper.startRecording();
        } else
            Log.e(TAG, "MediaRecorderWrapper is already started");
    }

    private void stopMediaRecorder(){
        if(mMediaRecorderWrapper != null && mMediaRecorderWrapper.isRecording){
            mMediaRecorderWrapper.stopRecording();
        }else
            Log.e(TAG, "MediaRecorderWrapper is not recording");
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        setupCameraWithSurfaceTexture(surface);
    }


    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {

    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        stopCamera();
        return false;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {

    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        setupCameraWithSurfaceHolder(holder);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {

    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        stopCamera();
    }
}