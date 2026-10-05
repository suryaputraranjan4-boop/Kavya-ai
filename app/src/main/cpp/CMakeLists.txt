cmake_minimum_required(VERSION 3.22.1)

project("kavya_offline")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)

# Pin the official llama.cpp Android-compatible source instead of the old
# in-repo inference stub. The native project is fetched at build time.
FetchContent_Declare(
    llama_cpp
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG b11425
    GIT_SHALLOW TRUE
)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)

FetchContent_MakeAvailable(llama_cpp)

find_library(log-lib log)

add_library(
    kavya_llama
    SHARED
    kavya_llama_jni.cpp
)

target_include_directories(
    kavya_llama
    PRIVATE
    ${llama_cpp_SOURCE_DIR}/include
)

target_link_libraries(
    kavya_llama
    PRIVATE
    llama
    ${log-lib}
)
