package net.openwatch.hwencoderexperiments.recorder;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Created by davidbrodsky on 10/21/13.
 * This class wraps a MediaMuxer with the purpose of
 * handling starting and stopping of the Muxer when all
 * tracks are added or finalized respectively.
 *
 * Usage:
 * MediaMuxerWrapper muxerWrapper = new MediaMuxerWrapper("/sdcard/test/", 0);
 * muxerWrapper.addTrack(videoMediaFormat);
 * muxerWrapper.addTrack(audioMediaFormat);
 * muxerWrapper.writeSampleData(trackIndex, encodedData, bufferInfo);
 * ...
 * muxerWrapper.finishTrack();
 * muxerWrapper.finishTrack();
 *
 */
class MediaMuxerWrapper {
    private static final String TAG = "MediaMuxerWrapper";
    private static final boolean VERBOSE = false;

    MediaMuxer muxer;
    String outputDirectory;
    int outputFormat = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4;
    int videoWidth = 640;
    int videoHeight = 480;
    int totalNumTracks = 2;
    boolean started = false;
    int chunk = 0;
    int numTracksAdded = 0;
    int numTracksFinished = 0;

    Object sync = new Object();

    public MediaMuxerWrapper(String outputDirectory, int chunk){
        this.outputDirectory = outputDirectory;
        this.chunk = chunk;
        restart(chunk);
    }

    public void setTotalNumTracks(int totalNumTracks){
        if(!started)
            this.totalNumTracks = totalNumTracks;
    }

    public void setVideoSize(int videoWidth, int videoHeight){
        if(!started){
            this.videoWidth = videoWidth;
            this.videoHeight = videoHeight;
        }
    }

    public void setOutputFormat(int outputFormat){
        if(!started)
            this.outputFormat = outputFormat;
    }

    public int addTrack(MediaFormat format){
        if(started) return -1;

        numTracksAdded++;
        int trackIndex = muxer.addTrack(format);
        if(numTracksAdded == totalNumTracks){
            if (VERBOSE) Log.i(TAG, "All tracks added, starting muxer");
            muxer.start();
            started = true;
        }
        return trackIndex;
    }

    public void writeSampleData(int index, ByteBuffer encodedData, MediaCodec.BufferInfo bufferInfo ){
        muxer.writeSampleData(index, encodedData, bufferInfo);
    }

    public void finishTrack(){
        if(!started) return;

        numTracksFinished++;
        if(numTracksFinished == totalNumTracks){
            if (VERBOSE) Log.i(TAG, "All tracks finished, stopping muxer");
            stop();
        }

    }

    public boolean allTracksAdded(){
        return (numTracksAdded == totalNumTracks);
    }

    public boolean allTracksFinished(){
        return (numTracksFinished == totalNumTracks);
    }


    public void stop(){
        if(muxer != null && started){
            if(!allTracksFinished()) Log.e(TAG, "Stopping Muxer before all tracks added!");
            if(!started) Log.e(TAG, "Stopping Muxer before it was started");
            muxer.stop();
            muxer.release();
            muxer = null;
            started = false;
            chunk = 0;
            numTracksAdded = 0;
            numTracksFinished = 0;
        }
    }

    private String outputPathForChunk(int chunk){
        return outputDirectory + videoWidth + "x" + videoHeight + "_" + chunk + ".mp4";
    }

    public void restart(int chunk){
        stop();
        try {
            muxer = new MediaMuxer(outputPathForChunk(chunk), outputFormat);
        } catch (IOException e) {
            throw new RuntimeException("MediaMuxer creation failed", e);
        }
    }
}