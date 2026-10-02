cmake_minimum_required(VERSION 3.22.1)

project("kavya_qwen")

# Configure C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Add Native JNI bridge source for Qwen3 GGUF inference
add_library(
    kavya_qwen
    SHARED
    kavya_qwen_jni.cpp
)

# Find Android logging library
find_library(
    log-lib
    log
)

# Link native libraries
target_link_libraries(
    kavya_qwen
    ${log-lib}
)
