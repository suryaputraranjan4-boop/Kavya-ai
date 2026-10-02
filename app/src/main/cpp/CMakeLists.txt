cmake_minimum_required(VERSION 3.22.1)

project("kavya_qwen")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Find Android log library
find_library(
    log-lib
    log
)

# Build llama native library
add_library(
    llama
    STATIC
    llama.cpp
)

# Build Kavya Qwen JNI shared library
add_library(
    kavya_qwen
    SHARED
    kavya_qwen_jni.cpp
)

# Link JNI bridge against llama and Android log
target_link_libraries(
    kavya_qwen
    llama
    ${log-lib}
)
