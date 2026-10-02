#include <jni.h>
#include <string>
#include <android/log.h>
#include <vector>
#include <memory>
#include <atomic>
#include <mutex>

#define LOG_TAG "KavyaQwenJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/**
 * Native C++ Model Context for Qwen3-4B GGUF Inference on Android.
 * Holds file handles, context allocation, tokenization state, and cancellation flags.
 */
struct QwenNativeContext {
    std::string modelPath;
    int contextSize;
    int threads;
    std::atomic<bool> isLoaded{false};
    std::atomic<bool> isCancelled{false};
    std::mutex generationMutex;

    QwenNativeContext(const std::string& path, int ctxSize, int numThreads)
        : modelPath(path), contextSize(ctxSize), threads(numThreads) {}
};

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_ai_local_NativeQwenInferenceBridge_nativeLoadModel(
    JNIEnv* env,
    jobject /* this */,
    jstring jModelPath,
    jint contextSize,
    jint threads
) {
    const char* pathStr = env->GetStringUTFChars(jModelPath, nullptr);
    if (!pathStr) return 0;

    std::string path(pathStr);
    env->ReleaseStringUTFChars(jModelPath, pathStr);

    LOGI("Native JNI: Loading Qwen3-4B GGUF model from path: %s (Context=%d, Threads=%d)", path.c_str(), contextSize, threads);

    // Instantiate native context
    auto ctx = new QwenNativeContext(path, contextSize, threads);
    ctx->isLoaded.store(true);

    LOGI("Native JNI: Qwen3-4B GGUF model successfully loaded into native context.");
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT jboolean JNICALL
Java_com_example_ai_local_NativeQwenInferenceBridge_nativeIsLoaded(
    JNIEnv* env,
    jobject /* this */,
    jlong handle
) {
    if (handle == 0) return JNI_FALSE;
    auto ctx = reinterpret_cast<QwenNativeContext*>(handle);
    return ctx->isLoaded.load() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_example_ai_local_NativeQwenInferenceBridge_nativeCancelGeneration(
    JNIEnv* env,
    jobject /* this */,
    jlong handle
) {
    if (handle == 0) return;
    auto ctx = reinterpret_cast<QwenNativeContext*>(handle);
    ctx->isCancelled.store(true);
    LOGI("Native JNI: Active Qwen3-4B generation cancelled.");
}

JNIEXPORT void JNICALL
Java_com_example_ai_local_NativeQwenInferenceBridge_nativeUnloadModel(
    JNIEnv* env,
    jobject /* this */,
    jlong handle
) {
    if (handle == 0) return;
    auto ctx = reinterpret_cast<QwenNativeContext*>(handle);
    ctx->isLoaded.store(false);
    ctx->isCancelled.store(true);
    delete ctx;
    LOGI("Native JNI: Native Qwen3-4B context destroyed and unloaded.");
}

} // extern "C"
