#include <jni.h>
#include <string>
#include <vector>
#include "llama.h"

struct KavyaLlamaHandle {
    llama_model * model = nullptr;
};

static std::string jstringToString(JNIEnv *env, jstring value) {
    if (!value) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string result = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return result;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_ai_offline_LlamaCppEngine_nativeLoad(
        JNIEnv *env, jobject /*thiz*/, jstring modelPath) {
    const std::string path = jstringToString(env, modelPath);
    if (path.empty()) return 0;

    llama_backend_init();

    llama_model_params params = llama_model_default_params();
    params.n_gpu_layers = 0;

    llama_model *model = llama_model_load_from_file(path.c_str(), params);
    if (!model) return 0;

    auto *handle = new KavyaLlamaHandle();
    handle->model = model;
    return reinterpret_cast<jlong>(handle);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_ai_offline_LlamaCppEngine_nativeGenerate(
        JNIEnv *env, jobject /*thiz*/, jlong handlePtr, jstring promptValue, jint maxTokens) {
    auto *handle = reinterpret_cast<KavyaLlamaHandle *>(handlePtr);
    if (!handle || !handle->model) return env->NewStringUTF("");

    const std::string prompt = jstringToString(env, promptValue);
    if (prompt.empty()) return env->NewStringUTF("");

    const llama_vocab *vocab = llama_model_get_vocab(handle->model);

    const int nPrompt = -llama_tokenize(
            vocab, prompt.c_str(), prompt.size(), nullptr, 0, true, true);
    if (nPrompt <= 0) return env->NewStringUTF("");

    std::vector<llama_token> promptTokens(nPrompt);
    if (llama_tokenize(
            vocab, prompt.c_str(), prompt.size(),
            promptTokens.data(), promptTokens.size(), true, true) < 0) {
        return env->NewStringUTF("");
    }

    llama_context_params ctxParams = llama_context_default_params();
    const uint32_t requestedCtx = llama_model_n_ctx_train(handle->model);
    const uint32_t contextSize = std::max<uint32_t>(
            512u,
            std::min<uint32_t>(requestedCtx ? requestedCtx : 2048u, 4096u));
    ctxParams.n_ctx = std::max<uint32_t>(contextSize, static_cast<uint32_t>(nPrompt + 64));
    ctxParams.n_batch = std::min<uint32_t>(ctxParams.n_ctx, 512u);
    ctxParams.n_threads = 4;
    ctxParams.n_threads_batch = 4;

    llama_context *ctx = llama_init_from_model(handle->model, ctxParams);
    if (!ctx) return env->NewStringUTF("");

    auto samplerParams = llama_sampler_chain_default_params();
    llama_sampler *sampler = llama_sampler_chain_init(samplerParams);
    llama_sampler_chain_add(sampler, llama_sampler_init_min_p(0.05f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.7f));
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
        return env->NewStringUTF("");
    }

    const int limit = std::max(1, std::min(maxTokens, 512));
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
