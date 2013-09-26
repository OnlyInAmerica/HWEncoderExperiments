# HWEncoderExperiments

Herein lies experiments with Android 4.3's [`MediaCodec`](http://developer.android.com/reference/android/media/MediaCodec.html) and [`MediaMuxer`](http://developer.android.com/reference/android/media/MediaMuxer.html) APIs.

The master branch is concerned with simultaneously producing a single high quality .mp4 as well as gapless 5 second chunk mp4s. The end goal is to allow an Android device to act as an [HLS](http://en.wikipedia.org/wiki/HTTP_Live_Streaming) / [MPEG-DASH](http://en.wikipedia.org/wiki/Dynamic_Adaptive_Streaming_over_HTTP) server.

The audioonly branch shows a barebones example encoding AAC audio with Android's [`AudioRecord`](http://developer.android.com/reference/android/media/AudioRecord.html) class.

## Output
Output is stored in '/sdcard/HWEncodingExperiments' in or internal storage if /sdcard isn't available.

Output location is modified via `FileUtils.OUTPUT_DIR` and `FileUtils.createTempFileInRootAppStorage`.


## Note on ColorFormats
This example doesn't yet intelligenty check for available color formats. If you experience a crash on `MediaCodec.configure`, try changing the appropriate part of `ChunkedAvcEncoder.prepare()`:


    private void prepare() {
    …
      videoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_TI_FormatYUV420PackedSemiPlanar);
      // On devices without a TI SOC, try:
      // COLOR_FormatYUV420PackedSemiPlanar
    …
    }

