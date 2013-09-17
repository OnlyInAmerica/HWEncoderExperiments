package net.openwatch.hwencoderexperiments;

import android.content.Context;
import android.media.*;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;

public class AudioEncodingTest {
    private static final String TAG = "EncoderTest";
    private static final boolean VERBOSE = true;

    private static final int kNumInputBytes = 256 * 1024;
    private static final long kTimeoutUs = 10000;

    private static MediaMuxer mMediaMuxer;

    public static void testAACEncoders(Context c) {
        LinkedList<MediaFormat> formats = new LinkedList<MediaFormat>();

        final int kAACProfiles[] = {2};

        final int kSampleRates[] = { /*8000, 11025, 22050, */ 44100 /*, 48000 */ };
        final int kBitRates[] = { /* 64000,*/ 128000 };

        for (int k = 0; k < kAACProfiles.length; ++k) {
            for (int i = 0; i < kSampleRates.length; ++i) {
                if (kAACProfiles[k] == 5 && kSampleRates[i] < 22050) {
                    // Is this right? HE does not support sample rates < 22050Hz?
                    continue;
                }
                for (int j = 0; j < kBitRates.length; ++j) {
                    for (int ch = 1; ch <= 2; ++ch) {
                        MediaFormat format  = new MediaFormat();
                        format.setString(MediaFormat.KEY_MIME, "audio/mp4a-latm");

                        format.setInteger(
                                MediaFormat.KEY_AAC_PROFILE, kAACProfiles[k]);

                        format.setInteger(
                                MediaFormat.KEY_SAMPLE_RATE, kSampleRates[i]);

                        format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, ch);
                        format.setInteger(MediaFormat.KEY_BIT_RATE, kBitRates[j]);
                        formats.push(format);
                    }
                }
            }
        }

        testEncoderWithFormats("audio/mp4a-latm", formats, c);
    }

    private static void testEncoderWithFormats(
            String mime, List<MediaFormat> formats, Context c) {
        List<String> componentNames = getEncoderNamesForType(mime);

        for (String componentName : componentNames) {
            Log.d(TAG, "testing component '" + componentName + "'");
            for (MediaFormat format : formats) {
                Log.d(TAG, "  testing format '" + format + "'");
                testEncoder(componentName, format, c);
            }
        }
    }

    private static List<String> getEncoderNamesForType(String mime) {
        LinkedList<String> names = new LinkedList<String>();

        int n = MediaCodecList.getCodecCount();
        for (int i = 0; i < n; ++i) {
            MediaCodecInfo info = MediaCodecList.getCodecInfoAt(i);

            if (!info.isEncoder()) {
                continue;
            }

            if (!info.getName().startsWith("OMX.")) {
                // Unfortunately for legacy reasons, "AACEncoder", a
                // non OMX component had to be in this list for the video
                // editor code to work... but it cannot actually be instantiated
                // using MediaCodec.
                Log.d(TAG, "skipping '" + info.getName() + "'.");
                continue;
            }

            String[] supportedTypes = info.getSupportedTypes();

            for (int j = 0; j < supportedTypes.length; ++j) {
                if (supportedTypes[j].equalsIgnoreCase(mime)) {
                    names.push(info.getName());
                    break;
                }
            }
        }

        return names;
    }

    private static int queueInputBuffer(
            MediaCodec codec, ByteBuffer[] inputBuffers, int index) {
        ByteBuffer buffer = inputBuffers[index];
        buffer.clear();

        int size = buffer.limit();

        byte[] zeroes = new byte[size];
        buffer.put(zeroes);

        codec.queueInputBuffer(index, 0 /* offset */, size, 0 /* timeUs */, 0);

        return size;
    }

    private static void dequeueOutputBuffer(
            MediaCodec codec, ByteBuffer[] outputBuffers,
            int index, MediaCodec.BufferInfo info) {
        codec.releaseOutputBuffer(index, false /* render */);
    }

    private static void testEncoder(String componentName, MediaFormat format, Context c) {
        int trackIndex = 0;
        boolean mMuxerStarted = false;
        File f = FileUtils.createTempFileInRootAppStorage(c, "aac_test_" + new Date().getTime() + ".mp4");
        MediaCodec codec = MediaCodec.createByCodecName(componentName);

        try {
            codec.configure(
                    format,
                    null /* surface */,
                    null /* crypto */,
                    MediaCodec.CONFIGURE_FLAG_ENCODE);
        } catch (IllegalStateException e) {
            Log.e(TAG, "codec '" + componentName + "' failed configuration.");

        }

        codec.start();

        try {
            mMediaMuxer = new MediaMuxer(f.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        } catch (IOException ioe) {
            throw new RuntimeException("MediaMuxer creation failed", ioe);
        }

        ByteBuffer[] codecInputBuffers = codec.getInputBuffers();
        ByteBuffer[] codecOutputBuffers = codec.getOutputBuffers();

        int numBytesSubmitted = 0;
        boolean doneSubmittingInput = false;
        int numBytesDequeued = 0;

        while (true) {
            int index;

            if (!doneSubmittingInput) {
                index = codec.dequeueInputBuffer(kTimeoutUs /* timeoutUs */);

                if (index != MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (numBytesSubmitted >= kNumInputBytes) {
                        Log.i(TAG, "queueing EOS to inputBuffer");
                        codec.queueInputBuffer(
                                index,
                                0 /* offset */,
                                0 /* size */,
                                0 /* timeUs */,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);

                        if (VERBOSE) {
                            Log.d(TAG, "queued input EOS.");
                        }

                        doneSubmittingInput = true;
                    } else {
                        int size = queueInputBuffer(
                                codec, codecInputBuffers, index);

                        numBytesSubmitted += size;

                        if (VERBOSE) {
                            Log.d(TAG, "queued " + size + " bytes of input data.");
                        }
                    }
                }
            }

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            index = codec.dequeueOutputBuffer(info, kTimeoutUs /* timeoutUs */);

            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
            } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat newFormat = codec.getOutputFormat();
                trackIndex = mMediaMuxer.addTrack(newFormat);
                mMediaMuxer.start();
                mMuxerStarted = true;
            } else if (index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                codecOutputBuffers = codec.getOutputBuffers();
            } else {
                // Write to muxer
                //dequeueOutputBuffer(codec, codecOutputBuffers, index, info);

                ByteBuffer encodedData = codecOutputBuffers[index];
                if (encodedData == null) {
                    throw new RuntimeException("encoderOutputBuffer " + index +
                            " was null");
                }

                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    // The codec config data was pulled out and fed to the muxer when we got
                    // the INFO_OUTPUT_FORMAT_CHANGED status.  Ignore it.
                    if (VERBOSE) Log.d(TAG, "ignoring BUFFER_FLAG_CODEC_CONFIG");
                    info.size = 0;
                }

                if (info.size != 0) {
                    if (!mMuxerStarted) {
                        throw new RuntimeException("muxer hasn't started");
                    }

                    // adjust the ByteBuffer values to match BufferInfo (not needed?)
                    encodedData.position(info.offset);
                    encodedData.limit(info.offset + info.size);

                    mMediaMuxer.writeSampleData(trackIndex, encodedData, info);
                    if (VERBOSE) Log.d(TAG, "sent " + info.size + " audio bytes to muxer with pts " + info.presentationTimeUs);
                }

                codec.releaseOutputBuffer(index, false);

                // End write to muxer
                numBytesDequeued += info.size;

                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    if (VERBOSE) {
                        Log.d(TAG, "dequeued output EOS.");
                    }
                    break;
                }

                if (VERBOSE) {
                    Log.d(TAG, "dequeued " + info.size + " bytes of output data.");
                }
            }
        }

        if (VERBOSE) {
            Log.d(TAG, "queued a total of " + numBytesSubmitted + "bytes, "
                    + "dequeued " + numBytesDequeued + " bytes.");
        }

        int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        int channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        int inBitrate = sampleRate * channelCount * 16;  // bit/sec
        int outBitrate = format.getInteger(MediaFormat.KEY_BIT_RATE);

        float desiredRatio = (float)outBitrate / (float)inBitrate;
        float actualRatio = (float)numBytesDequeued / (float)numBytesSubmitted;

        if (actualRatio < 0.9 * desiredRatio || actualRatio > 1.1 * desiredRatio) {
            Log.w(TAG, "desiredRatio = " + desiredRatio
                    + ", actualRatio = " + actualRatio);
        }


        codec.release();
        mMediaMuxer.stop();
        mMediaMuxer.release();
        codec = null;
    }
}