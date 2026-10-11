cmake_minimum_required(VERSION 3.22)
project(chuse LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# The inference library and its JNI bridge are one artefact; splitting them buys nothing on
# a single-ABI phone build and costs a copy of the model code.
add_subdirectory(third_party/llama.cpp EXCLUDE_FROM_ALL)

add_library(chuse SHARED chuse_jni.cpp)

target_include_directories(chuse PRIVATE third_party/llama.cpp/include)

target_link_libraries(
    chuse
    PRIVATE
        llama
        android
        log
)