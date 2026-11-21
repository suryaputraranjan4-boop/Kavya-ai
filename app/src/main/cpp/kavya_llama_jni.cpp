#include <jni.h>
#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <mutex>
#include <string>
#include <vector>
#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

static std::once_flag gLlamaBackendOnce;

static void ensureLlamaBackendInit() {
    std::call_once(gLlamaBackendOnce, []() {
        llama_backend_init();
    });
}

struct KavyaLlamaHandle {
    llama_model * model = nullptr;
    mtmd_context * mtmdCtx = nullptr;
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

    const int32_t maxBatch = std::max<int32_t>(1, static_cast<int32_t>(ctxParams.n_batch));
    llama_batch batch = llama_batch_init(maxBatch, 0, 1);

    const int32_t totalPrompt = static_cast<int32_t>(promptTokens.size());
    for (int32_t offset = 0; offset < totalPrompt; offset += maxBatch) {
        const int32_t chunkSize = std::min<int32_t>(maxBatch, totalPrompt - offset);
        const bool isLastChunk = (offset + chunkSize >= totalPrompt);
        batch.n_tokens = chunkSize;
        for (int32_t j = 0; j < chunkSize; ++j) {
            batch.token[j] = promptTokens[offset + j];
            batch.pos[j] = static_cast<llama_pos>(offset + j);
            batch.n_seq_id[j] = 1;
            batch.seq_id[j][0] = 0;
            batch.logits[j] = (isLastChunk && j == chunkSize - 1) ? 1 : 0;
        }
        if (llama_decode(ctx, batch) != 0) {
            llama_batch_free(batch);
            llama_sampler_free(sampler);
            llama_free(ctx);
            return "";
        }
    }

    std::string output;
    const int limit = std::max(1, std::min(static_cast<int>(maxTokens), 512));
    for (int i = 0; i < limit; ++i) {
        const llama_token token = llama_sampler_sample(sampler, ctx, -1);
        if (llama_vocab_is_eog(vocab, token)) break;

        char piece[256];
        const int n = llama_token_to_piece(vocab, token, piece, sizeof(piece), 0, true);
        if (n > 0) output.append(piece, n);

        llama_sampler_accept(sampler, token);

        batch.n_tokens = 1;
        batch.token[0] = token;
        batch.pos[0] = static_cast<llama_pos>(totalPrompt + i);
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

static std::string runMtmdMultimodalCompletion(
        KavyaLlamaHandle *handle,
        const unsigned char *rgbData,
        jint width,
        jint height,
        std::string prompt,
        jint maxTokens) {
    if (!handle || !handle->model || !handle->mtmdCtx || !rgbData || width <= 0 || height <= 0) {
        return "";
    }
    if (!mtmd_support_vision(handle->mtmdCtx)) {
        return "";
    }

    mtmd_bitmap *bmp = mtmd_bitmap_init(
            static_cast<uint32_t>(width),
            static_cast<uint32_t>(height),
            rgbData);
    if (!bmp) return "";

    const char *activeMarkerRaw = mtmd_get_marker(handle->mtmdCtx);
    const std::string marker = (activeMarkerRaw && activeMarkerRaw[0] != '\0')
            ? std::string(activeMarkerRaw)
            : std::string(mtmd_default_marker());
    const std::string legacyToken = "<image>";
    const size_t legacyPos = prompt.find(legacyToken);
    if (legacyPos != std::string::npos) {
        prompt.replace(legacyPos, legacyToken.size(), marker);
    } else if (prompt.find(marker) == std::string::npos) {
        prompt = marker + "\n" + prompt;
    }

    mtmd_input_chunks *chunks = mtmd_input_chunks_init();
    if (!chunks) {
        mtmd_bitmap_free(bmp);
        return "";
    }

    mtmd_input_text inpText{};
    inpText.text = prompt.c_str();
    inpText.text_len = prompt.size();
    inpText.add_special = true;
    inpText.parse_special = true;

    const mtmd_bitmap *bitmaps[1] = { bmp };
    const int32_t tokRes = mtmd_tokenize(handle->mtmdCtx, chunks, &inpText, bitmaps, 1);
    if (tokRes != 0) {
        mtmd_input_chunks_free(chunks);
        mtmd_bitmap_free(bmp);
        return "";
    }

    const llama_pos nPromptPos = mtmd_helper_get_n_pos(chunks);
    const uint32_t requestedCtx = llama_model_n_ctx_train(handle->model);
    const uint32_t capCtx = static_cast<uint32_t>(std::max(1024, handle->configuredCtx));
    const uint32_t contextSize = std::max<uint32_t>(
            1024u,
            std::min<uint32_t>(requestedCtx ? requestedCtx : capCtx, capCtx));
    if (nPromptPos <= 0 || static_cast<uint32_t>(nPromptPos + 64) > contextSize) {
        mtmd_input_chunks_free(chunks);
        mtmd_bitmap_free(bmp);
        return "";
    }

    llama_context_params ctxParams = llama_context_default_params();
    ctxParams.n_ctx = contextSize;
    ctxParams.n_batch = std::min<uint32_t>(contextSize, 1024u);
    ctxParams.n_ubatch = ctxParams.n_batch;
    ctxParams.n_threads = std::max(1, handle->configuredThreads);
    ctxParams.n_threads_batch = std::max(1, handle->configuredThreads);

    llama_context *ctx = llama_init_from_model(handle->model, ctxParams);
    if (!ctx) {
        mtmd_input_chunks_free(chunks);
        mtmd_bitmap_free(bmp);
        return "";
    }

    llama_pos n_past = 0;
    const int32_t evalRes = mtmd_helper_eval_chunks(
            handle->mtmdCtx,
            ctx,
            chunks,
            /*n_past=*/0,
            /*seq_id=*/0,
            static_cast<int32_t>(ctxParams.n_batch),
            /*logits_last=*/true,
            &n_past);

    mtmd_input_chunks_free(chunks);
    mtmd_bitmap_free(bmp);

    if (evalRes != 0) {
        llama_free(ctx);
        return "";
    }

    const llama_vocab *vocab = llama_model_get_vocab(handle->model);
    auto samplerParams = llama_sampler_chain_default_params();
    llama_sampler *sampler = llama_sampler_chain_init(samplerParams);
    llama_sampler_chain_add(sampler, llama_sampler_init_min_p(0.05f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.2f));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    llama_batch batch = llama_batch_init(1, 0, 1);
    std::string output;
    const int limit = std::max(1, std::min(static_cast<int>(maxTokens), 512));
    for (int i = 0; i < limit; ++i) {
        const llama_token token = llama_sampler_sample(sampler, ctx, -1);
        if (llama_vocab_is_eog(vocab, token)) break;

        char piece[256];
        const int n = llama_token_to_piece(vocab, token, piece, sizeof(piece), 0, true);
        if (n > 0) output.append(piece, n);

        llama_sampler_accept(sampler, token);

        batch.n_tokens = 1;
        batch.token[0] = token;
        batch.pos[0] = n_past++;
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

    ensureLlamaBackendInit();

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

    ensureLlamaBackendInit();

    llama_model_params params = llama_model_default_params();
    params.n_gpu_layers = 0;

    llama_model *model = llama_model_load_from_file(mPath.c_str(), params);
    if (!model) return 0;

    const int32_t clampedThreads = std::max(1, std::min(static_cast<int>(threads), 8));
    mtmd_context_params mparams = mtmd_context_params_default();
    mparams.use_gpu = false;
    mparams.print_timings = false;
    mparams.n_threads = clampedThreads;
    mparams.warmup = true;
    const mtmd_memory_usage memUsage = mtmd_get_memory_usage(pPath.c_str(), mparams);
    if (memUsage.use_non_causal && memUsage.image_max_tokens > 1024) {
        mparams.image_max_tokens = 1024;
        mparams.image_min_tokens = std::min(mparams.image_min_tokens, 1024);
    }

    mtmd_context *mtmdCtx = mtmd_init_from_file(pPath.c_str(), model, mparams);
    if (!mtmdCtx || !mtmd_support_vision(mtmdCtx)) {
        if (mtmdCtx) mtmd_free(mtmdCtx);
        llama_model_free(model);
        return 0;
    }

    auto *handle = new KavyaLlamaHandle();
    handle->model = model;
    handle->mtmdCtx = mtmdCtx;
    handle->mmprojPath = pPath;
    handle->configuredCtx = std::max(1024, static_cast<int>(contextSize));
    handle->configuredThreads = clampedThreads;
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
    if (!handle || !handle->model || !handle->mtmdCtx || handle->mmprojPath.empty()) {
        return env->NewStringUTF("");
    }
    if (!rgbBytes || width <= 0 || height <= 0) {
        return env->NewStringUTF("");
    }
    const jsize byteLen = env->GetArrayLength(rgbBytes);
    const int64_t requiredBytes = static_cast<int64_t>(width) * static_cast<int64_t>(height) * 3LL;
    if (requiredBytes <= 0 || static_cast<int64_t>(byteLen) < requiredBytes) {
        return env->NewStringUTF("");
    }

    jbyte *rawRgb = env->GetByteArrayElements(rgbBytes, nullptr);
    if (!rawRgb) return env->NewStringUTF("");

    std::vector<unsigned char> rgbCopy(
            reinterpret_cast<unsigned char *>(rawRgb),
            reinterpret_cast<unsigned char *>(rawRgb) + static_cast<size_t>(requiredBytes));
    env->ReleaseByteArrayElements(rgbBytes, rawRgb, JNI_ABORT);

    const std::string prompt = jstringToString(env, promptValue);
    const std::string output = runMtmdMultimodalCompletion(
            handle, rgbCopy.data(), width, height, prompt, maxTokens);
    return env->NewStringUTF(output.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_ai_offline_LlamaCppEngine_nativeFree(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handlePtr) {
    auto *handle = reinterpret_cast<KavyaLlamaHandle *>(handlePtr);
    if (!handle) return;
    if (handle->mtmdCtx) mtmd_free(handle->mtmdCtx);
    if (handle->model) llama_model_free(handle->model);
    delete handle;
}
