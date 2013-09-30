LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_LDLIBS += -llog -lz 
LOCAL_SRC_FILES := ColorConversion.c
LOCAL_CFLAGS := -march=armv7-a -mfloat-abi=softfp -mfpu=neon
LOCAL_MODULE := ColorConversion

include $(BUILD_SHARED_LIBRARY)
