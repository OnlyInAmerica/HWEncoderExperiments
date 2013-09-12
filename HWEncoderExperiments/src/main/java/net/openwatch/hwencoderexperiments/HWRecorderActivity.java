package net.openwatch.hwencoderexperiments;

import android.app.Activity;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.os.Bundle;
import android.util.Log;
import android.view.TextureView;
import android.view.View;

import java.io.IOException;

public class HWRecorderActivity extends Activity implements TextureView.SurfaceTextureListener{
    private static final String TAG = "CameraToMpegTest";

    Camera mCamera;
    AvcEncoder mEncoder;
    boolean recording = false;
    int bufferSize = 460800;

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
            mEncoder = new AvcEncoder(getApplicationContext());

            mCamera.addCallbackBuffer(new byte[bufferSize]);
            mCamera.addCallbackBuffer(new byte[bufferSize]);
            mCamera.addCallbackBuffer(new byte[bufferSize]);
            mCamera.setPreviewCallbackWithBuffer(new Camera.PreviewCallback() {
                @Override
                public void onPreviewFrame(byte[] data, Camera camera) {
                    mEncoder.offerEncoder(data);
                    mCamera.addCallbackBuffer(data);
                }
            });
        }else{
            if(mEncoder != null){
                mEncoder.stop();
            }

        }
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        mCamera = Camera.open();

        try {
            mCamera.setPreviewTexture(surface);
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