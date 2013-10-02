package net.openwatch.hwencoderexperiments;

import android.app.Activity;
import android.opengl.EGL14;
import android.opengl.EGLContext;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class HWRecorderActivity extends Activity {
    private static final String TAG = "CameraToMpegTest";
    boolean recording = false;
    ChunkedHWRecorder chunkedHWRecorder;

    GLSurfaceView glSurfaceView;
    GlSurfaceViewRenderer glSurfaceViewRenderer = new GlSurfaceViewRenderer();

    protected void onCreate (Bundle savedInstanceState){
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_hwrecorder);
        glSurfaceView = (GLSurfaceView) findViewById(R.id.glSurfaceView);
        glSurfaceView.setRenderer(glSurfaceViewRenderer);
    }

    @Override
    public void onPause(){
        super.onPause();
        glSurfaceView.onPause();
    }

    @Override
    public void onResume(){
        super.onResume();
        glSurfaceView.onResume();
    }

    public void onRunTestButtonClicked(View v){
        if(!recording){
            try {
                startChunkedHWRecorder();
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
    public void startChunkedHWRecorder() throws Throwable {
        chunkedHWRecorder = new ChunkedHWRecorder();
        chunkedHWRecorder.setDisplayEGLContext(context);
        ChunkedHWRecorderWrapper.runTest(chunkedHWRecorder);
    }


    /**
     * Wraps encodeCameraToMpeg().  This is necessary because SurfaceTexture will try to use
     * the looper in the current thread if one exists, and the CTS tests create one on the
     * test thread.
     * <p/>
     * The wrapper propagates exceptions thrown by the worker thread back to the caller.
     */
    private static class ChunkedHWRecorderWrapper implements Runnable {
        private Throwable mThrowable;
        private ChunkedHWRecorder chunkedHwRecorder;

        private ChunkedHWRecorderWrapper(ChunkedHWRecorder recorder) {
            chunkedHwRecorder = recorder;
        }

        /**
         * Entry point.
         */
        public static void runTest(ChunkedHWRecorder obj) throws Throwable {
            ChunkedHWRecorderWrapper wrapper = new ChunkedHWRecorderWrapper(obj);
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
                chunkedHwRecorder.startRecording(null);
            } catch (Throwable th) {
                mThrowable = th;
            }
        }
    }

    static EGLContext context;

    public class GlSurfaceViewRenderer implements GLSurfaceView.Renderer{

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            Log.i(TAG, "GLSurfaceView created");
            context = EGL14.eglGetCurrentContext();
            if(context == EGL14.EGL_NO_CONTEXT)
                Log.e(TAG, "failed to get valid EGLContext");

           EGL14.eglMakeCurrent(EGL14.eglGetCurrentDisplay(), EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int width, int height) {

        }

        @Override
        public void onDrawFrame(GL10 gl) {
        }
    }

}