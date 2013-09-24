package net.openwatch.hwencoderexperiments;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import java.util.concurrent.ArrayBlockingQueue;

/*
 * This class polls audio from the microphone and fills a
 * circular buffer.
 *
 * Data is accessed by calling emptyBuffer(), which returns
 * all available unread samples. The output length is guaranteed to be a multiple of
 * recorderTask.samples_per_frame for convenient input to an audio encoder
 *
 * Usage:
 *
 * 1. AudioSoftwarePoller recorder = new AudioSoftwarePoller();
 * 1a (optional): recorder.setSamplesPerFrame(NUM_SAMPLES_PER_CODEC_FRAME)
 * 2. recorder.startPolling();
 * 3. byte[] audio_data = recorder.emptyBuffer();
 * 4. recorder.stopPolling();
 */
public class AudioSoftwarePoller {
    public static final String TAG = "AudioSoftwarePoller";
    public static final int SAMPLE_RATE = 44100;
    public static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    public static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    public static final int FRAMES_PER_BUFFER = 24; // 1 sec @ 1024 samples/frame (aac)
    public static long US_PER_FRAME = 0;
    public static boolean is_recording = false;
    final boolean VERBOSE = false;
    public RecorderTask recorderTask = new RecorderTask();

    ChunkedAvcEncoder avcEncoder;

    public AudioSoftwarePoller() {
    }


    public void setChunkedAvcEncoder(ChunkedAvcEncoder avcEncoder) {
        this.avcEncoder = avcEncoder;
    }

    /**
     * Set the number of samples per frame (Default is 1024). Call this before startPolling().
     * The output of emptyBuffer() will be equal to, or a multiple of, this value.
     *
     * @param samples_per_frame The desired audio frame size in samples.
     */
    public void setSamplesPerFrame(int samples_per_frame) {
        if (!is_recording)
            recorderTask.samples_per_frame = samples_per_frame;
    }

    /**
     * Return the number of microseconds represented by each audio frame
     * calculated with the sampling rate and samples per frame
     * @return
     */
    public long getMicroSecondsPerFrame(){
        if(US_PER_FRAME == 0){
            US_PER_FRAME = (SAMPLE_RATE / recorderTask.samples_per_frame) * 1000000;
        }
        return US_PER_FRAME;
    }

    public void recycleInputBuffer(byte[] buffer){
        recorderTask.data_buffer.offer(buffer);
    }

    /**
     * Begin polling audio and transferring it to the buffer. Call this before emptyBuffer().
     */
    public void startPolling() {
        new Thread(recorderTask).start();
    }

    /**
     * Stop polling audio.
     */
    public void stopPolling() {
        is_recording = false;        // will stop recording after next sample received
        // by recorderTask
    }

    public class RecorderTask implements Runnable {
        public int buffer_size;
        //public int samples_per_frame = 1024;    // codec-specific
        public int samples_per_frame = 2048;    // codec-specific
        public int buffer_write_index = 0;        // last buffer index written to
        public int buffer_read_index = 0;        // first buffer index to read from
        //public byte[] data_buffer;
        public int total_frames_written = 0;
        public int total_frames_read = 0;

        ArrayBlockingQueue<byte[]> data_buffer = new ArrayBlockingQueue<byte[]>(50);

        int read_result = 0;

        public void run() {
            int min_buffer_size = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);

            buffer_size = samples_per_frame * FRAMES_PER_BUFFER;

            // Ensure buffer is adequately sized for the AudioRecord
            // object to initialize
            if (buffer_size < min_buffer_size)
                buffer_size = ((min_buffer_size / samples_per_frame) + 1) * samples_per_frame * 2;

            //data_buffer = new byte[samples_per_frame]; // filled directly by hardware
            for(int x=0; x < 25; x++)
                data_buffer.add(new byte[samples_per_frame]);

            AudioRecord audio_recorder;
            audio_recorder = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,       // source
                    SAMPLE_RATE,                         // sample rate, hz
                    CHANNEL_CONFIG,                      // channels
                    AUDIO_FORMAT,                        // audio format
                    buffer_size);                        // buffer size (bytes)


            audio_recorder.startRecording();
            is_recording = true;
            Log.i("AudioSoftwarePoller", "SW recording begin");
            long audioPresentationTimeNs;
            while (is_recording) {
                //read_result = audio_recorder.read(data_buffer, buffer_write_index, samples_per_frame);
                audioPresentationTimeNs = System.nanoTime();
                byte[] this_buffer;
                if(data_buffer.isEmpty()){
                    this_buffer = new byte[samples_per_frame];
                    //Log.i(TAG, "Audio buffer empty. added new buffer");
                }else{
                    this_buffer = data_buffer.poll();
                }
                read_result = audio_recorder.read(this_buffer, 0, samples_per_frame);
                if (VERBOSE)
                    Log.i("AudioSoftwarePoller-FillBuffer", String.valueOf(buffer_write_index) + " - " + String.valueOf(buffer_write_index + samples_per_frame - 1));
                if(read_result == AudioRecord.ERROR_BAD_VALUE || read_result == AudioRecord.ERROR_INVALID_OPERATION)
                    Log.e("AudioSoftwarePoller", "Read error");
                //buffer_write_index = (buffer_write_index + samples_per_frame) % buffer_size;
                total_frames_written++;
                if(avcEncoder != null){
                    avcEncoder.offerAudioEncoder(this_buffer, audioPresentationTimeNs);
                }
            }
            if (audio_recorder != null) {
                audio_recorder.setRecordPositionUpdateListener(null);
                audio_recorder.release();
                audio_recorder = null;
                Log.i("AudioSoftwarePoller", "stopped");
            }
        }
    }

}