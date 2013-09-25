package net.openwatch.hwencoderexperiments;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;

public class HWRecorderActivity extends Activity {
    private static final String TAG = "CameraToMpegTest";

    AudioEncoder mEncoder;
    AudioSoftwarePoller audioPoller;
    boolean recording = false;

    Button recordButton;

    protected void onCreate (Bundle savedInstanceState){
        super.onCreate(savedInstanceState);
        getActionBar().setTitle("");
        setContentView(R.layout.activity_hwrecorder);
        recordButton = (Button) findViewById(R.id.recordButton);

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

        if(recording){
            recordButton.setText(R.string.stop_recording);

            mEncoder = new AudioEncoder(getApplicationContext());
            audioPoller = new AudioSoftwarePoller();
            audioPoller.setAudioEncoder(mEncoder);
            mEncoder.setAudioSoftwarePoller(audioPoller);
            audioPoller.startPolling();
        }else{
            recordButton.setText(R.string.start_recording);
            if(mEncoder != null){
                audioPoller.stopPolling();
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
}