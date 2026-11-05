#include <jni.h>

#include <array>
#include <cstddef>

#include "cadence/wasm_api.h"

extern "C" JNIEXPORT jlong JNICALL
Java_com_campus_sport_NativeCadenceEngine_nativeCreate(
    JNIEnv* /*env*/, jobject /*thiz*/, jint exercise) {
  return reinterpret_cast<jlong>(cadence_create_session(static_cast<int>(exercise)));
}

extern "C" JNIEXPORT void JNICALL
Java_com_campus_sport_NativeCadenceEngine_nativeDestroy(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
  if (handle != 0) {
    cadence_destroy_session(reinterpret_cast<void*>(handle));
  }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_campus_sport_NativeCadenceEngine_nativeProcess(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle,
    jfloatArray landmarks,
    jfloat elapsed_seconds) {
  if (handle == 0 || landmarks == nullptr) {
    return env->NewStringUTF("{\"error\":\"invalid session\"}");
  }
  const jsize length = env->GetArrayLength(landmarks);
  if (length != 33 * 4) {
    return env->NewStringUTF("{\"error\":\"expected 132 floats\"}");
  }

  std::array<float, 33 * 4> buffer{};
  env->GetFloatArrayRegion(landmarks, 0, length, buffer.data());
  const char* result = cadence_process_landmarks(
      reinterpret_cast<void*>(handle),
      buffer.data(),
      33,
      static_cast<float>(elapsed_seconds));
  return env->NewStringUTF(result != nullptr ? result : "{\"error\":\"null result\"}");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_campus_sport_NativeCadenceEngine_nativeSummary(
    JNIEnv* env, jobject /*thiz*/, jlong handle) {
  if (handle == 0) {
    return env->NewStringUTF("{\"error\":\"invalid session\"}");
  }
  const char* result = cadence_session_summary(reinterpret_cast<void*>(handle));
  return env->NewStringUTF(result != nullptr ? result : "{\"error\":\"null summary\"}");
}
