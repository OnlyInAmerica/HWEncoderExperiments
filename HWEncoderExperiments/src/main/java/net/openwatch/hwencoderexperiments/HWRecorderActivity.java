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

    // testing
    long lastFrameTime = 0;

    protected void onCreate (Bundle savedInstanceState){
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera_to_mpeg_test);
        TextureView tv = (TextureView) findViewById(R.id.cameraPreview);
        tv.setSurfaceTextureListener(this);
    }

    public void onRecordButtonClick(View v){
        recording = !recording;
        Log.i(TAG, "Record button hit. Start: " + String.valueOf(recording));

        if(recording){
            mEncoder = new ChunkedAvcEncoder(getApplicationContext());

            mCamera.addCallbackBuffer(new byte[bufferSize]);
            mCamera.addCallbackBuffer(new byte[bufferSize]);
            mCamera.addCallbackBuffer(new byte[bufferSize]);
            mCamera.addCallbackBuffer(new byte[bufferSize]);
            mCamera.addCallbackBuffer(new byte[bufferSize]);
            mCamera.addCallbackBuffer(new byte[bufferSize]);
            mCamera.setPreviewCallbackWithBuffer(new Camera.PreviewCallback() {
                @Override
                public void onPreviewFrame(byte[] data, Camera camera) {
                    numFramesPreviewed++;
                    //Log.i(TAG, "Inter-frame time: " + (System.currentTimeMillis() - lastFrameTime) + " ms");
                    mEncoder.offerEncoder(data);
                    mCamera.addCallbackBuffer(data);
                    lastFrameTime = System.currentTimeMillis();
                    if(!recording){ // One frame must be sent with EOS flag after stop requested
                        camera.setPreviewCallbackWithBuffer(null);
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