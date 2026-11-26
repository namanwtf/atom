cmake_minimum_required(VERSION 3.22)
project(atom_llama C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if (NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
endif ()
# Release = -O3 for everything, including the llama.cpp/ggml sources pulled in below.
set(CMAKE_C_FLAGS_RELEASE   "-O3 -DNDEBUG -ffunction-sections -fdata-sections" CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG -ffunction-sections -fdata-sections" CACHE STRING "" FORCE)

set(LLAMA_DIR ${CMAKE_CURRENT_LIST_DIR}/../../../third_party/llama.cpp)

# ---- llama.cpp / ggml configuration -----------------------------------------------------------
# One static build linked into a single JNI library.
set(BUILD_SHARED_LIBS     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES  OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP       OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_UI        OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON    OFF CACHE BOOL "" FORCE)   # we only use the C API in include/llama.h
set(LLAMA_OPENSSL         OFF CACHE BOOL "" FORCE)   # not in the NDK stable API set
set(LLAMA_CONNECT         OFF CACHE BOOL "" FORCE)
set(LLAMA_ALL_WARNINGS    OFF CACHE BOOL "" FORCE)
set(GGML_ALL_WARNINGS     OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE           OFF CACHE BOOL "" FORCE)

# OpenMP worker threads spin-wait between ops and burn battery; ggml's own threadpool sleeps.
set(GGML_OPENMP           OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE        ON  CACHE BOOL "" FORCE)   # tinyBLAS sgemm kernels for prompt processing
# Cross-compiling: never probe the build host. Fixed, conservative baseline that runs on any
# ARMv8.2 phone with the dot-product extension (Cortex-A55/A75 era onward).
set(GGML_NATIVE           OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_ARCH     "armv8.2-a+dotprod" CACHE STRING "" FORCE)
# Runtime CPU-variant dispatch (GGML_CPU_ALL_VARIANTS) needs GGML_BACKEND_DL + shared libs, which
# conflicts with the single static library; left off on purpose.
set(GGML_BACKEND_DL       OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI     OFF CACHE BOOL "" FORCE)   # would download sources at configure time

add_subdirectory(${LLAMA_DIR} llama.cpp EXCLUDE_FROM_ALL)

# ---- JNI bridge -------------------------------------------------------------------------------
add_library(atom_llama SHARED atom_llama.cpp)

target_link_libraries(atom_llama PRIVATE llama ggml android log)

target_compile_options(atom_llama PRIVATE
        -O3
        -fvisibility=hidden
        -fvisibility-inlines-hidden
        -ffunction-sections
        -fdata-sections)

# Only the JNI entry points are exported; dead code from the static archives is dropped.
target_link_options(atom_llama PRIVATE
        -Wl,--gc-sections
        -Wl,--exclude-libs,ALL
        -Wl,--as-needed
        -Wl,--strip-all)
