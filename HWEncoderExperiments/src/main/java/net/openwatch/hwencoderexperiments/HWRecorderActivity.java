package net.openwatch.hwencoderexperiments;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;

public class HWRecorderActivity extends Activity {
    private static final String TAG = "CameraToMpegTest";
    boolean recording = false;
    ChunkedHWRecorder chunkedHWRecorder;
    Button theButton;

    protected void onCreate (Bundle savedInstanceState){
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera_to_mpeg_test);
    }

    public void onRunTestButtonClicked(View v){
        if(!recording){
            try {
                testEncodeCameraToMp4();
                recording = true;
                ((Button) v).setText("Stop Recording");
            } catch (Throwable throwable) {
                throwable.printStackTrace();
            }
        }else{
            chunkedHWRecorder.stopRecording();
            recording = false;
            ((Button) v).setText("Start Recording");
        }
    }

    /**
     * test entry point
     */
    public void testEncodeCameraToMp4() throws Throwable {
        chunkedHWRecorder = new ChunkedHWRecorder();
        CameraToMpegWrapper.runTest(chunkedHWRecorder);
    }


    /**
     * Wraps encodeCameraToMpeg().  This is necessary because SurfaceTexture will try to use
     * the looper in the current thread if one exists, and the CTS tests create one on the
     * test thread.
     * <p/>
     * The wrapper propagates exceptions thrown by the worker thread back to the caller.
     */
    private static class CameraToMpegWrapper implements Runnable {
        private Throwable mThrowable;
        private ChunkedHWRecorder mTest;

        private CameraToMpegWrapper(ChunkedHWRecorder test) {
            mTest = test;
        }

        /**
         * Entry point.
         */
        public static void runTest(ChunkedHWRecorder obj) throws Throwable {
            CameraToMpegWrapper wrapper = new CameraToMpegWrapper(obj);
            Thread th = new Thread(wrapper, "codec test");
            th.start();
            // When th.join() is called, blocks thread which catches onFrameAvailable
            //th.join();
            if (wrapper.mThrowable != null) {
                throw wrapper.mThrowable;
            }
        }

        @Override
        public void run() {
            try {
                mTest.startRecording(null);
            } catch (Throwable th) {
                mThrowable = th;
            }
        }
    }

}