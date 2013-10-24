package net.openwatch.hwencoderexperiments;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import net.openwatch.ffmpegwrapper.FFmpegWrapper;
import net.openwatch.hwencoderexperiments.recorder.ChunkedHWRecorder;

public class HWRecorderActivity extends Activity {
    private static final String TAG = "CameraToMpegTest";
    boolean recording = false;
    ChunkedHWRecorder chunkedHWRecorder;
    FFmpegWrapper fFmpegWrapper;

    //GLSurfaceView glSurfaceView;
    //GlSurfaceViewRenderer glSurfaceViewRenderer = new GlSurfaceViewRenderer();

    protected void onCreate (Bundle savedInstanceState){
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_hwrecorder);
        //glSurfaceView = (GLSurfaceView) findViewById(R.id.glSurfaceView);
        //glSurfaceView.setRenderer(glSurfaceViewRenderer);
        fFmpegWrapper = new FFmpegWrapper();
    }

    @Override
    public void onPause(){
        super.onPause();
        //glSurfaceView.onPause();
    }

    @Override
    public void onResume(){
        super.onResume();
        //glSurfaceView.onResume();
    }

    public void onRecordButtonClicked(View v){
        if(!recording){
            try {
                chunkedHWRecorder = new ChunkedHWRecorder(getApplicationContext());
                chunkedHWRecorder.startRecording(null);
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

    /*
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
    */

}