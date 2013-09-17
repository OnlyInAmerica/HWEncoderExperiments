package net.openwatch.hwencoderexperiments;

import android.app.Activity;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.os.Bundle;
import android.util.Log;
import android.view.TextureView;
import android.view.View;

import java.io.IOException;
import java.util.List;

public class HWRecorderActivity extends Activity implements TextureView.SurfaceTextureListener{
    private static final String TAG = "CameraToMpegTest";

    Camera mCamera;
    ChunkedAvcEncoder mEncoder;
    boolean recording = false;
    int bufferSize = 460800;
    int numFramesPreviewed = 0;

    AudioSoftwarePoller audioPoller;

    // testing
    long lastFrameTime = 0;

    protected void onCreate (Bundle savedInstanceState){
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera_to_mpeg_test);
        TextureView tv = (TextureView) findViewById(R.id.cameraPreview);
        tv.setSurfaceTextureListener(this);

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

    public void onRecordButtonClick(View v){
        recording = !recording;
        Log.i(TAG, "Record button hit. Start: " + String.valueOf(recording));
        AudioEncodingTest.testAACEncoders(getApplicationContext());
        /*
        if(recording){
            audioPoller = new AudioSoftwarePoller();
            audioPoller.startPolling();

            mEncoder = new ChunkedAvcEncoder(getApplicationContext());
            final byte[] dummyAudio = new byte[343];
            mCamera.addCallbackBuffer(new byte[bufferSize]);
            mCamera.addCallbackBuffer(new byte[bufferSize]);
            mCamera.addCallbackBuffer(new byte[bufferSize]);
            mCamera.addCallbackBuffer(new byte[bufferSize]);
            mCamera.addCallbackBuffer(new byte[bufferSize]);
            mCamera.addCallbackBuffer(new byte[bufferSize]);
            mCamera.setPreviewCallbackWithBuffer(new Camera.PreviewCallback() {
                @Override
                public void onPreviewFrame(byte[] data, Camera camera) {
                    if(!audioPoller.is_recording)
                        return;
                    numFramesPreviewed++;
                    //Log.i(TAG, "Inter-frame time: " + (System.currentTimeMillis() - lastFrameTime) + " ms");
                    mEncoder.offerVideoEncoder(data);
                    //mEncoder.offerAudioEncoder(audioPoller.emptyBuffer());
                    mEncoder.offerAudioEncoder(dummyAudio);
                    mCamera.addCallbackBuffer(data);
                    lastFrameTime = System.currentTimeMillis();
                    if(!recording){ // One frame must be sent with EOS flag after stop requested
                        camera.setPreviewCallbackWithBuffer(null);
                        audioPoller.stopPolling();
                    }
                }
            });
            lastFrameTime = System.currentTimeMillis();
        }else{
            if(mEncoder != null){
                mEncoder.stop();
            }
            Log.i(TAG, "HWRecorderActivity saw #frames: " + numFramesPreviewed);

        }
        */
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        mCamera = Camera.open();

        try {
            mCamera.setPreviewTexture(surface);
            Camera.Parameters parameters = mCamera.getParameters();
            List<int[]> fpsRanges = parameters.getSupportedPreviewFpsRange();
            int[] maxFpsRange = fpsRanges.get(fpsRanges.size() - 1);
            parameters.setPreviewFpsRange(maxFpsRange[0], maxFpsRange[1]);
            mCamera.setParameters(parameters);
            mCamera.setDisplayOrientation(90);
            mCamera.startPreview();
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {

    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        mCamera.stopPreview();
        mCamera.release();
        return false;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {

    }

}