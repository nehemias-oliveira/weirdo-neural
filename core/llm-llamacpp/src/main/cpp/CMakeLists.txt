cmake_minimum_required(VERSION 3.22.1)
project(weirdo_llm CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Caminho do submodule llama.cpp (relativo a este CMakeLists.txt)
set(LLAMA_CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../third_party/llama.cpp)

# Opções para deixar o build do llama.cpp o mais enxuto possível
set(LLAMA_BUILD_TESTS     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES  OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER    OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL            OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS     OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP           OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE        OFF CACHE BOOL "" FORCE)

# Adiciona o llama.cpp como subdiretório. Ele exporta o target `llama`
add_subdirectory(${LLAMA_CPP_DIR} ${CMAKE_BINARY_DIR}/llama_build)

# Nossa biblioteca nativa (JNI bridge)
add_library(weirdo_llm SHARED llama_jni.cpp)

target_link_libraries(weirdo_llm PRIVATE llama android log)

# Flags de otimização
target_compile_options(weirdo_llm PRIVATE -O3 -ffast-math -fexceptions)
