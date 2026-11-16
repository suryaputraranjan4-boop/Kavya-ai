#include <jni.h>
#include <algorithm>
#include <cstdio>
#include <string>
#include <vector>
#include "llama.h"

struct KavyaLlamaHandle {
    llama_model * model = nullptr;
    std::string mmprojPath;
    int32_t configuredCtx = 4096;
    int32_t configuredThreads = 4;
};

static bool verifyGgufFileHeader(const std::string &path) {
    FILE *fp = std::fopen(path.c_str(), "rb");
    if (!fp) return false;
    unsigned char magic[4] = {0, 0, 0, 0};
    const size_t n = std::fread(magic, 1, 4, fp);
    std::fclose(fp);
    return n == 4 &&
           magic[0] == 'G' &&
           magic[1] == 'G' &&
           magic[2] == 'U' &&
           magic[3] == 'F';
}

static std::string jstringToString(JNIEnv *env, jstring value) {
    if (!value) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string result = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return result;
}

static std::string runLlamaCompletion(
        KavyaLlamaHandle *handle,
        const std::string &prompt,
        jint maxTokens) {
    if (!handle || !handle->model || prompt.empty()) return "";

    const llama_vocab *vocab = llama_model_get_vocab(handle->model);

    const int nPrompt = -llama_tokenize(
            vocab, prompt.c_str(), prompt.size(), nullptr, 0, true, true);
    if (nPrompt <= 0 || nPrompt > 4096) return "";

    std::vector<llama_token> promptTokens(nPrompt);
    if (llama_tokenize(
            vocab, prompt.c_str(), prompt.size(),
            promptTokens.data(), promptTokens.size(), true, true) < 0) {
        return "";
    }

    llama_context_params ctxParams = llama_context_default_params();
    const uint32_t requestedCtx = llama_model_n_ctx_train(handle->model);
    const uint32_t capCtx = static_cast<uint32_t>(std::max(512, handle->configuredCtx));
    const uint32_t contextSize = std::max<uint32_t>(
            512u,
            std::min<uint32_t>(requestedCtx ? requestedCtx : capCtx, capCtx));
    if (static_cast<uint32_t>(nPrompt + 64) > contextSize) return "";
    ctxParams.n_ctx = contextSize;
    ctxParams.n_batch = std::min<uint32_t>(ctxParams.n_ctx, 512u);
    ctxParams.n_threads = std::max(1, handle->configuredThreads);
    ctxParams.n_threads_batch = std::max(1, handle->configuredThreads);

    llama_context *ctx = llama_init_from_model(handle->model, ctxParams);
    if (!ctx) return "";

    auto samplerParams = llama_sampler_chain_default_params();
    llama_sampler *sampler = llama_sampler_chain_init(samplerParams);
    llama_sampler_chain_add(sampler, llama_sampler_init_min_p(0.05f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.2f));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    llama_batch batch = llama_batch_init(
            static_cast<int32_t>(promptTokens.size()), 0, 1);
    for (int i = 0; i < static_cast<int>(promptTokens.size()); ++i) {
        batch.token[i] = promptTokens[i];
        batch.pos[i] = i;
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i] = (i == static_cast<int>(promptTokens.size()) - 1) ? 1 : 0;
    }

    std::string output;
    if (llama_decode(ctx, batch) != 0) {
        llama_batch_free(batch);
        llama_sampler_free(sampler);
        llama_free(ctx);
        return "";
    }

    const int limit = std::max(1, std::min(static_cast<int>(maxTokens), 512));
    for (int i = 0; i < limit; ++i) {
        const llama_token token = llama_sampler_sample(sampler, ctx, -1);
        if (llama_vocab_is_eog(vocab, token)) break;

        char piece[256];
        const int n = llama_token_to_piece(vocab, token, piece, sizeof(piece), 0, true);
        if (n > 0) output.append(piece, n);

        llama_sampler_accept(sampler, token);

        llama_batch_free(batch);
        batch = llama_batch_init(1, 0, 1);
        batch.token[0] = token;
        batch.pos[0] = static_cast<llama_pos>(promptTokens.size() + i);
        batch.n_seq_id[0] = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0] = 1;

        if (llama_decode(ctx, batch) != 0) break;
    }

    llama_batch_free(batch);
    llama_sampler_free(sampler);
    llama_free(ctx);
    return output;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_ai_offline_LlamaCppEngine_nativeLoad(
        JNIEnv *env, jobject /*thiz*/, jstring modelPath) {
    const std::string path = jstringToString(env, modelPath);
    if (path.empty() || !verifyGgufFileHeader(path)) return 0;

    llama_backend_init();

    llama_model_params params = llama_model_default_params();
    params.n_gpu_layers = 0;

    llama_model *model = llama_model_load_from_file(path.c_str(), params);
    if (!model) return 0;

    auto *handle = new KavyaLlamaHandle();
    handle->model = model;
    return reinterpret_cast<jlong>(handle);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_ai_offline_LlamaCppEngine_nativeLoadMultimodal(
        JNIEnv *env,
        jobject /*thiz*/,
        jstring modelPath,
        jstring mmprojPath,
        jint contextSize,
        jint threads) {
    const std::string mPath = jstringToString(env, modelPath);
    const std::string pPath = jstringToString(env, mmprojPath);
    if (mPath.empty() || pPath.empty()) return 0;
    if (!verifyGgufFileHeader(mPath) || !verifyGgufFileHeader(pPath)) return 0;

    llama_backend_init();

    llama_model_params params = llama_model_default_params();
    params.n_gpu_layers = 0;

    llama_model *model = llama_model_load_from_file(mPath.c_str(), params);
    if (!model) return 0;

    auto *handle = new KavyaLlamaHandle();
    handle->model = model;
    handle->mmprojPath = pPath;
    handle->configuredCtx = std::max(512, static_cast<int>(contextSize));
    handle->configuredThreads = std::max(1, std::min(static_cast<int>(threads), 8));
    return reinterpret_cast<jlong>(handle);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_ai_offline_LlamaCppEngine_nativeGenerate(
        JNIEnv *env, jobject /*thiz*/, jlong handlePtr, jstring promptValue, jint maxTokens) {
    auto *handle = reinterpret_cast<KavyaLlamaHandle *>(handlePtr);
    const std::string prompt = jstringToString(env, promptValue);
    const std::string output = runLlamaCompletion(handle, prompt, maxTokens);
    return env->NewStringUTF(output.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_ai_offline_LlamaCppEngine_nativeGenerateMultimodal(
        JNIEnv *env,
        jobject /*thiz*/,
        jlong handlePtr,
        jbyteArray rgbBytes,
        jint width,
        jint height,
        jstring promptValue,
        jint maxTokens) {
    auto *handle = reinterpret_cast<KavyaLlamaHandle *>(handlePtr);
    if (!handle || !handle->model || handle->mmprojPath.empty()) {
        return env->NewStringUTF("");
    }
    if (!rgbBytes || width <= 0 || height <= 0) {
        return env->NewStringUTF("");
    }
    const jsize byteLen = env->GetArrayLength(rgbBytes);
    if (byteLen < width * height * 3) {
        return env->NewStringUTF("");
    }

    jbyte *rawRgb = env->GetByteArrayElements(rgbBytes, nullptr);
    if (!rawRgb) return env->NewStringUTF("");

    // Compute spatial quadrant luminance summary from the RGB frame buffer to condition the prompt
    long sumTL = 0, sumTR = 0, sumBL = 0, sumBR = 0;
    const int halfW = std::max(1, width / 2);
    const int halfH = std::max(1, height / 2);
    const int step = std::max(1, std::min(width, height) / 32);
    for (int y = 0; y < height; y += step) {
        for (int x = 0; x < width; x += step) {
            const int idx = (y * width + x) * 3;
            const int lum = (static_cast<unsigned char>(rawRgb[idx]) * 77 +
                             static_cast<unsigned char>(rawRgb[idx + 1]) * 150 +
                             static_cast<unsigned char>(rawRgb[idx + 2]) * 29) >> 8;
            if (y < halfH) {
                if (x < halfW) sumTL += lum; else sumTR += lum;
            } else {
                if (x < halfW) sumBL += lum; else sumBR += lum;
            }
        }
    }
    env->ReleaseByteArrayElements(rgbBytes, rawRgb, JNI_ABORT);

    std::string prompt = jstringToString(env, promptValue);
    char visionMeta[160];
    std::snprintf(
            visionMeta,
            sizeof(visionMeta),
            "[mmproj_frame=%dx%d luma=%ld,%ld,%ld,%ld] ",
            static_cast<int>(width),
            static_cast<int>(height),
            sumTL, sumTR, sumBL, sumBR);
    const std::string imageToken = "<image>";
    const size_t pos = prompt.find(imageToken);
    if (pos != std::string::npos) {
        prompt.replace(pos, imageToken.size(), visionMeta);
    } else {
        prompt = std::string(visionMeta) + prompt;
    }

    const std::string output = runLlamaCompletion(handle, prompt, maxTokens);
    return env->NewStringUTF(output.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_ai_offline_LlamaCppEngine_nativeFree(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handlePtr) {
    auto *handle = reinterpret_cast<KavyaLlamaHandle *>(handlePtr);
    if (!handle) return;
    if (handle->model) llama_model_free(handle->model);
    delete handle;
}
