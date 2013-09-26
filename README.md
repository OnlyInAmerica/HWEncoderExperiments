HWEncoderExperiments
====================

Herein lies experiments with Android 4.3's [`MediaCodec`](http://developer.android.com/reference/android/media/MediaCodec.html) and [`MediaMuxer`](http://developer.android.com/reference/android/media/MediaMuxer.html) APIs.

The master branch is concerned with simultaneously producing a single high quality .mp4 as well as gapless 5 second chunk mp4s. The end goal is to allow an Android device to act as an [HLS](http://en.wikipedia.org/wiki/HTTP_Live_Streaming) / [MPEG-DASH](http://en.wikipedia.org/wiki/Dynamic_Adaptive_Streaming_over_HTTP) server.

The audioonly branch shows a barebones example encoding AAC audio with Android's [`AudioRecord`](http://developer.android.com/reference/android/media/AudioRecord.html) class.

