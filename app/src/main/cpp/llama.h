#ifndef LLAMA_H
#define LLAMA_H

#include <stddef.h>
#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef int32_t llama_pos;
typedef int32_t llama_token;
typedef int32_t llama_seq_id;

struct llama_model;
struct llama_context;
struct llama_vocab;

struct llama_model_params {
    int32_t n_gpu_layers;
    bool main_gpu;
    bool vocab_only;
    bool use_mmap;
    bool use_mlock;
    bool check_tensors;
};

struct llama_context_params {
    uint32_t n_ctx;
    uint32_t n_batch;
    uint32_t n_ubatch;
    uint32_t n_seq_max;
    int32_t n_threads;
    int32_t n_threads_batch;
    bool logits_all;
    bool embeddings;
    bool offload_kqv;
};

struct llama_model_params llama_model_default_params(void);
struct llama_context_params llama_context_default_params(void);

struct llama_model * llama_model_load_from_file(const char * path_model, struct llama_model_params params);
void llama_free_model(struct llama_model * model);

struct llama_context * llama_init_from_model(struct llama_model * model, struct llama_context_params params);
void llama_free(struct llama_context * ctx);

const struct llama_vocab * llama_model_get_vocab(const struct llama_model * model);

int32_t llama_tokenize(
    const struct llama_vocab * vocab,
    const char * text,
    int32_t text_len,
    llama_token * tokens,
    int32_t max_tokens,
    bool add_special,
    bool parse_special
);

int32_t llama_token_to_piece(
    const struct llama_vocab * vocab,
    llama_token token,
    char * buf,
    int32_t length,
    int32_t lstrip,
    bool special
);

llama_token llama_vocab_bos(const struct llama_vocab * vocab);
llama_token llama_vocab_eos(const struct llama_vocab * vocab);

#ifdef __cplusplus
}
#endif

#endif // LLAMA_H
