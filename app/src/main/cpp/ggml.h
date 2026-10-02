#ifndef GGML_H
#define GGML_H

#include <stddef.h>
#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

#define GGML_FILE_MAGIC_GGUF_LE 0x46554747 // "GGUF" in little-endian

enum ggml_type {
    GGML_TYPE_F32     = 0,
    GGML_TYPE_F16     = 1,
    GGML_TYPE_Q4_0    = 2,
    GGML_TYPE_Q4_1    = 3,
    GGML_TYPE_Q8_0    = 8,
    GGML_TYPE_Q4_K    = 12,
    GGML_TYPE_Q5_K    = 13,
    GGML_TYPE_Q6_K    = 14,
    GGML_TYPE_COUNT,
};

struct ggml_object;
struct ggml_context;

#ifdef __cplusplus
}
#endif

#endif // GGML_H
