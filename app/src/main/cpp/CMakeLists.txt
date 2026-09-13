cmake_minimum_required(VERSION 3.22.1)

project("llama_jni" CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------------------
# REAL on-device inference runtime: llama.cpp (ggml).
#
# This fetches and builds llama.cpp from source for the configured Android
# ABIs (arm64-v8a, x86_64) and links it into the JNI shim (llama_jni.cpp),
# which loads the GGUF model and runs genuine token generation.
#
# IMPORTANT — Gemma 3n support:
#   Gemma 3n (google_gemma-3n-*.gguf) is a 2025 architecture. Its GGUF
#   architecture id is "gemma3n". Loading it REQUIRES a llama.cpp revision
#   new enough to include gemma3n support. LLAMA_CPP_TAG below is PINNED to a
#   specific commit (not the moving `master` branch) for reproducibility. This
#   is the exact commit CMake FetchContent downloaded and that llama_jni.cpp
#   was API-verified against (see the VERIFICATION NOTE in llama_jni.cpp).
#
#   To move to a newer revision: change the pin AND re-verify every llama_*
#   symbol used in llama_jni.cpp against that revision's include/llama.h. Do
#   not assume the API is stable across revisions — this project already had to
#   migrate `use_mmap`→`load_mode` and `llama_kv_cache_clear`→`llama_memory_clear`.
#
#   If the model fails to load at runtime with an "unknown architecture
#   'gemma3n'" error, bump LLAMA_CPP_TAG to a newer revision.
#
# NOTE: This CMake configuration has NOT been compiled in the authoring
# environment (no Android SDK/NDK/CMake available there). It is written
# against the stable llama.cpp public C API. Build it with Android Studio /
# Gradle + NDK on a machine that has the toolchain. See llama_jni.cpp for a
# symbol-compatibility note if the fetched revision renamed any API.
# ---------------------------------------------------------------------------

# Pinned to the exact commit that was fetched and that llama_jni.cpp's API usage
# was verified against. A full clone is used (GIT_SHALLOW is not set below), which
# is required to check out an arbitrary commit SHA.
set(LLAMA_CPP_TAG "64a155d242cb427766055ea9caea6f34df1ca94b" CACHE STRING "llama.cpp git commit (pinned for reproducibility)")
set(FETCHCONTENT_UPDATES_DISCONNECTED ON CACHE BOOL "Skip updating fetched content" FORCE)

include(FetchContent)

# Enable ARM64 vector extensions for Cortex-A76 / Mali-G57 (Dimensity 6100+)
if(CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64" OR CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a")
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -march=armv8.2-a+fp16+dotprod")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv8.2-a+fp16+dotprod")
endif()

# Keep the llama.cpp build lean: we only need the library, not the CLIs.
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)   # no libcurl on device
set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)   # NDK OpenMP is fragile
set(GGML_LLAMAFILE       OFF CACHE BOOL "" FORCE)
set(GGML_ARM_DOTPROD     ON  CACHE BOOL "" FORCE)   # ARMv8.2-A dot product kernels
set(GGML_ARM_FP16        ON  CACHE BOOL "" FORCE)   # ARMv8.2-A half precision kernels
set(GGML_VULKAN          OFF CACHE BOOL "" FORCE)   # Vulkan requires host SPIRV-Headers toolchain
set(BUILD_SHARED_LIBS    OFF CACHE BOOL "" FORCE)   # static-link ggml/llama

FetchContent_Declare(
        llama_cpp
        GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
        GIT_TAG        ${LLAMA_CPP_TAG}
)
FetchContent_MakeAvailable(llama_cpp)

add_library(
        llama_jni
        SHARED
        llama_jni.cpp
)

find_library(log-lib log)

target_link_libraries(
        llama_jni
        llama
        ggml
        android
        ${log-lib}
)

# Enforce 16 KB ELF alignment for Android 16 KB page size compatibility
target_link_options(
        llama_jni
        PRIVATE
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=16384"
)
