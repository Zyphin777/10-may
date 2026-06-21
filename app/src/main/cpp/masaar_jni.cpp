// masaar_jni.cpp
#include <jni.h>
#include <string>
#include "masaar_core.h"

extern "C" JNIEXPORT void JNICALL
Java_com_example_offlineroutingapp_nativebridge_MasaarBridge_setNodeId(
        JNIEnv* env, jobject, jstring id) {
    const char* c_id = env->GetStringUTFChars(id, nullptr);
    setNodeId(c_id);
    env->ReleaseStringUTFChars(id, c_id);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_offlineroutingapp_nativebridge_MasaarBridge_buildMessage(
        JNIEnv* env, jobject, jstring payload, jstring dst) {
    const char* c_payload = env->GetStringUTFChars(payload, nullptr);
    const char* c_dst = env->GetStringUTFChars(dst, nullptr);

    std::string json = buildMessage(c_payload, c_dst);

    env->ReleaseStringUTFChars(payload, c_payload);
    env->ReleaseStringUTFChars(dst, c_dst);

    return env->NewStringUTF(json.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_offlineroutingapp_nativebridge_MasaarBridge_handleIncoming(
        JNIEnv* env, jobject, jstring msg) {
    const char* c_msg = env->GetStringUTFChars(msg, nullptr);

    std::string result = handleIncoming(c_msg);

    env->ReleaseStringUTFChars(msg, c_msg);

    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_offlineroutingapp_nativebridge_MasaarBridge_buildHelloMessage(
        JNIEnv* env, jobject /* this */) {

    std::string json = buildHelloMessage();
    return env->NewStringUTF(json.c_str());
}

// Created by rawan on 10/8/2025.
