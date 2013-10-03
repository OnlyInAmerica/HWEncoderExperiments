package net.openwatch.hwencoderexperiments;

import android.content.Context;
import android.hardware.Camera;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.util.Log;

import java.io.IOException;

/**
 * Created by davidbrodsky on 9/23/13.
 */
public class MediaRecorderWrapper {
    static final String TAG = "MediaRecorderWrapper";

    Camera mCamera;
    MediaRecorder mMediaRecorder;
    Context c;
    String outputLocation;

    boolean recordAudio = false;
    boolean isRecording = false;

    public MediaRecorderWrapper(Context c, String outputLocation, Camera camera){
        mCamera = camera;
        this.c = c;
        this.outputLocation = outputLocation;
    }

    public MediaRecorderWrapper recordAudio(boolean recordAudio){
        this.recordAudio = recordAudio;
        return this;
    }

    private boolean prepareVideoRecorder(){
        if(mCamera == null)
            return false;

        mMediaRecorder = new MediaRecorder();
        mCamera.unlock();
        mMediaRecorder.setCamera(mCamera);
        if(recordAudio) mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
        mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);

        // Step 3: Set a CamcorderProfile (requires API Level 8 or higher)
        if(recordAudio)
            mMediaRecorder.setProfile(CamcorderProfile.get(CamcorderProfile.QUALITY_720P));
        else{
            mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            mMediaRecorder.setVideoEncodingBitRate(2500000);
            mMediaRecorder.setVideoSize(640, 480);
            mMediaRecorder.setVideoFrameRate(30);
        }

        // Step 4: Set output file
        mMediaRecorder.setOutputFile(outputLocation);

        // Step 6: Prepare configured MediaRecorder
        try {
            mMediaRecorder.prepare();
        } catch (IllegalStateException e) {
            Log.d(TAG, "IllegalStateException preparing MediaRecorder: " + e.getMessage());
            releaseMediaRecorder();
            return false;
        } catch (IOException e) {
            Log.d(TAG, "IOException preparing MediaRecorder: " + e.getMessage());
            releaseMediaRecorder();
            return false;
        }
        return true;
    }

    public boolean startRecording(){
        if (prepareVideoRecorder()) {
            // Camera is available and unlocked, MediaRecorder is prepared,
            // now you can start recording
            mMediaRecorder.start();

            // inform the user that recording has started
            isRecording = true;
        }else
            isRecording = false;
        return isRecording;
    }

    public boolean stopRecording(){
        mMediaRecorder.stop();
        releaseMediaRecorder();
        isRecording = false;
        return isRecording;
    }

    private void releaseMediaRecorder(){
        if (mMediaRecorder != null) {
            mMediaRecorder.reset();   // clear recorder configuration
            mMediaRecorder.release(); // release the recorder object
            mMediaRecorder = null;
            mCamera.lock();           // lock camera for later use
        }
    }
}