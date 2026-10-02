#include <jni.h>
#include <string>
#include <android/log.h>
#include <vector>
#include <memory>
#include <atomic>
#include <mutex>
#include "llama.h"

#define LOG_TAG "KavyaQwenJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static std::string g_last_error = "";

/**
 * Native C++ Model Context for Qwen3-4B GGUF Inference on Android.
 * Holds file handles, context allocation, tokenization state, and cancellation flags.
 */
struct QwenNativeContext {
    std::string modelPath;
    int contextSize;
    int threads;
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    std::atomic<bool> isLoaded{false};
    std::atomic<bool> isCancelled{false};
    std::mutex generationMutex;

    QwenNativeContext(const std::string& path, int ctxSize, int numThreads)
        : modelPath(path), contextSize(ctxSize), threads(numThreads) {}

    ~QwenNativeContext() {
        if (ctx) {
            llama_free(ctx);
            ctx = nullptr;
        }
        if (model) {
            llama_free_model(model);
            model = nullptr;
        }
    }
};

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_example_ai_local_NativeQwenInferenceBridge_nativeGetLastError(
    JNIEnv* env,
    jobject /* this */
) {
    return env->NewStringUTF(g_last_error.c_str());
}

JNIEXPORT jlong JNICALL
Java_com_example_ai_local_NativeQwenInferenceBridge_nativeLoadModel(
    JNIEnv* env,
    jobject /* this */,
    jstring jModelPath,
    jint contextSize,
    jint threads
) {
    const char* pathStr = env->GetStringUTFChars(jModelPath, nullptr);
    if (!pathStr) {
        g_last_error = "Invalid model path parameter";
        return 0;
    }

    std::string path(pathStr);
    env->ReleaseStringUTFChars(jModelPath, pathStr);

    LOGI("Native JNI: Loading Qwen3-4B GGUF model via llama.cpp from path: %s (Context=%d, Threads=%d)", path.c_str(), contextSize, threads);

    // Initialize llama.cpp model params
    llama_model_params mparams = llama_model_default_params();
    mparams.use_mmap = true;

    llama_model* model = llama_model_load_from_file(path.c_str(), mparams);
    if (!model) {
        LOGE("Native JNI: Failed to load GGUF model from %s", path.c_str());
        g_last_error = "Failed to parse GGUF model file or invalid GGUF magic signature.";
        return 0;
    }

    // Initialize llama.cpp context params
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = contextSize > 0 ? contextSize : 4096;
    cparams.n_threads = threads > 0 ? threads : 4;

    llama_context* lctx = llama_init_from_model(model, cparams);
    if (!lctx) {
        LOGE("Native JNI: Failed to create llama context for model %s", path.c_str());
        llama_free_model(model);
        g_last_error = "Failed to allocate llama context for Qwen3-4B model.";
        return 0;
    }

    // Instantiate native context
    auto nativeCtx = new QwenNativeContext(path, contextSize, threads);
    nativeCtx->model = model;
    nativeCtx->ctx = lctx;
    nativeCtx->isLoaded.store(true);

    LOGI("Native JNI: Qwen3-4B GGUF model successfully loaded into native llama context.");
    return reinterpret_cast<jlong>(nativeCtx);
}

JNIEXPORT jboolean JNICALL
Java_com_example_ai_local_NativeQwenInferenceBridge_nativeIsLoaded(
    JNIEnv* env,
    jobject /* this */,
    jlong handle
) {
    if (handle == 0) return JNI_FALSE;
    auto ctx = reinterpret_cast<QwenNativeContext*>(handle);
    return (ctx && ctx->isLoaded.load()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_example_ai_local_NativeQwenInferenceBridge_nativeCancelGeneration(
    JNIEnv* env,
    jobject /* this */,
    jlong handle
) {
    if (handle == 0) return;
    auto ctx = reinterpret_cast<QwenNativeContext*>(handle);
    if (ctx) {
        ctx->isCancelled.store(true);
        LOGI("Native JNI: Active Qwen3-4B generation cancelled.");
    }
}

JNIEXPORT void JNICALL
Java_com_example_ai_local_NativeQwenInferenceBridge_nativeUnloadModel(
    JNIEnv* env,
    jobject /* this */,
    jlong handle
) {
    if (handle == 0) return;
    auto ctx = reinterpret_cast<QwenNativeContext*>(handle);
    if (ctx) {
        ctx->isLoaded.store(false);
        ctx->isCancelled.store(true);
        delete ctx;
        LOGI("Native JNI: Native Qwen3-4B context destroyed and unloaded.");
    }
}

} // extern "C"
