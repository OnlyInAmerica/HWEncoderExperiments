#include <jni.h>
#include <string.h>
#include <stdio.h>
#include <android/log.h>

#include <math.h>

#define LOG_TAG "ColorConversion"
#define LOGI(...)  __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...)  __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)


int y_frame_size = 640 * 480;
int q_frame_size = y_frame_size / 4;

jbyte Java_net_openwatch_hwencoderexperiments_ChunkedAvcEncoder_convertColors(JNIEnv * env, jobject this, jbyteArray j_in, jbyteArray j_out){
	jbyte *in = (*env)->GetByteArrayElements(env, j_in, NULL);
	jbyte *out = (*env)->GetByteArrayElements(env, j_out, NULL);

    memcpy(out, in, y_frame_size);				  // Y
    int x;
	for(x = 0; x < q_frame_size; x++){
		out[0] = in[y_frame_size + q_frame_size];   // Cb (U)
		out[1] = in[y_frame_size];				  // Cr (V)
		in+=1; out+=2;
	}

	/*
	System.arraycopy(input, 0, output, 0, frameSize); // Y

        for (int i = 0; i < qFrameSize; i++) {
            output[frameSize + i*2] = input[frameSize + i + qFrameSize]; // Cb (U)
            output[frameSize + i*2 + 1] = input[frameSize + i]; // Cr (V)
        }
        return output;
    */

	(*env)->ReleaseByteArrayElements(env, j_in, in, 0);

	return out;
}