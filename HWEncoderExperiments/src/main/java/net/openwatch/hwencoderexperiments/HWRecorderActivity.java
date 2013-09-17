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

public class HWRecorderActivity extends Activity{
    private static final String TAG = "CameraToMpegTest";

    ChunkedAvcEncoder mEncoder;
    boolean recording = false;

    AudioSoftwarePoller audioPoller;


    protected void onCreate (Bundle savedInstanceState){
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera_to_mpeg_test);

        // test MediaCodec capabilities
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
        if(recording)
            AudioEncodingTest.testAACEncoders(getApplicationContext());
    }
}