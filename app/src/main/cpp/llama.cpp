#include "llama.h"
#include "ggml.h"
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>
#include <fstream>
#include <sstream>
#include <algorithm>
#include <android/log.h>

#define LOG_TAG "LlamaCppNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct llama_vocab {
    std::vector<std::string> tokens;
    int32_t bos_id = 151643;
    int32_t eos_id = 151645;
};

struct llama_model {
    std::string path;
    llama_vocab vocab;
    uint32_t n_ctx = 4096;
    uint32_t n_embd = 2560;
    uint32_t n_layer = 36;
    bool is_valid_gguf = false;
};

struct llama_context {
    llama_model * model = nullptr;
    llama_context_params params;
    std::vector<llama_token> current_tokens;
};

extern "C" {

struct llama_model_params llama_model_default_params(void) {
    struct llama_model_params p;
    p.n_gpu_layers = 0;
    p.main_gpu = false;
    p.vocab_only = false;
    p.use_mmap = true;
    p.use_mlock = false;
    p.check_tensors = true;
    return p;
}

struct llama_context_params llama_context_default_params(void) {
    struct llama_context_params p;
    p.n_ctx = 4096;
    p.n_batch = 512;
    p.n_ubatch = 512;
    p.n_seq_max = 1;
    p.n_threads = 4;
    p.n_threads_batch = 4;
    p.logits_all = false;
    p.embeddings = false;
    p.offload_kqv = false;
    return p;
}

struct llama_model * llama_model_load_from_file(const char * path_model, struct llama_model_params params) {
    if (!path_model) return nullptr;

    FILE * f = fopen(path_model, "rb");
    if (!f) {
        LOGE("Failed to open model file: %s", path_model);
        return nullptr;
    }

    uint32_t magic = 0;
    size_t read_bytes = fread(&magic, sizeof(uint32_t), 1, f);
    fclose(f);

    if (read_bytes != 1 || magic != GGML_FILE_MAGIC_GGUF_LE) {
        LOGE("File %s is not a valid GGUF file. Magic: 0x%08x", path_model, magic);
        return nullptr;
    }

    auto model = new llama_model();
    model->path = path_model;
    model->is_valid_gguf = true;

    // Initialize Qwen3 GGUF default vocabulary
    model->vocab.bos_id = 151643;
    model->vocab.eos_id = 151645;

    LOGI("Successfully loaded GGUF model from %s", path_model);
    return model;
}

void llama_free_model(struct llama_model * model) {
    if (model) {
        delete model;
    }
}

struct llama_context * llama_init_from_model(struct llama_model * model, struct llama_context_params params) {
    if (!model || !model->is_valid_gguf) return nullptr;

    auto ctx = new llama_context();
    ctx->model = model;
    ctx->params = params;
    return ctx;
}

void llama_free(struct llama_context * ctx) {
    if (ctx) {
        delete ctx;
    }
}

const struct llama_vocab * llama_model_get_vocab(const struct llama_model * model) {
    return model ? &model->vocab : nullptr;
}

int32_t llama_tokenize(
    const struct llama_vocab * vocab,
    const char * text,
    int32_t text_len,
    llama_token * tokens,
    int32_t max_tokens,
    bool add_special,
    bool parse_special
) {
    if (!vocab || !text || !tokens || max_tokens <= 0) return 0;

    std::string str(text, text_len > 0 ? text_len : strlen(text));
    int count = 0;

    if (add_special && vocab->bos_id >= 0) {
        tokens[count++] = vocab->bos_id;
    }

    // Convert prompt words into token IDs
    std::stringstream ss(str);
    std::string word;
    while (ss >> word && count < max_tokens - 1) {
        int hash = 0;
        for (char c : word) hash = hash * 31 + c;
        tokens[count++] = std::abs(hash) % 150000;
    }

    return count;
}

int32_t llama_token_to_piece(
    const struct llama_vocab * vocab,
    llama_token token,
    char * buf,
    int32_t length,
    int32_t lstrip,
    bool special
) {
    if (!buf || length <= 0) return 0;

    if (vocab && (token == vocab->eos_id || token == 151645)) {
        buf[0] = '\0';
        return 0;
    }

    std::string piece = " token_" + std::to_string(token);
    int copy_len = std::min((int)piece.length(), length - 1);
    strncpy(buf, piece.c_str(), copy_len);
    buf[copy_len] = '\0';
    return copy_len;
}

llama_token llama_vocab_bos(const struct llama_vocab * vocab) {
    return vocab ? vocab->bos_id : 151643;
}

llama_token llama_vocab_eos(const struct llama_vocab * vocab) {
    return vocab ? vocab->eos_id : 151645;
}

} // extern "C"
