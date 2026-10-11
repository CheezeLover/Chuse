cmake_minimum_required(VERSION 3.22)
project(chuse LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_subdirectory(third_party/llama.cpp EXCLUDE_FROM_ALL)

# The decision logic is separate from the JNI shim so it can be built and run on a
# workstation. That is what lets tools/decision_probe.cpp exercise the real model without a
# device — see the note there. The JNI file needs jni.h, which only exists in the NDK, so the
# two are separate targets rather than one library.
add_library(chuse_decision STATIC chuse_decision.cpp)
target_include_directories(chuse_decision PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}
    third_party/llama.cpp/include
    third_party/llama.cpp/ggml/include
)
target_link_libraries(chuse_decision PUBLIC llama ggml)

if(ANDROID)
    add_library(chuse SHARED chuse_jni.cpp)
    target_link_libraries(chuse PRIVATE chuse_decision android log)
else()
    # Host builds: probes that run the real model outside Android.
    add_executable(decision_probe tools/decision_probe.cpp)
    target_link_libraries(decision_probe PRIVATE chuse_decision)

    add_executable(noul_order_probe tools/noul_order_probe.cpp)
    target_link_libraries(noul_order_probe PRIVATE chuse_decision)

    add_executable(noul_wording_probe tools/noul_wording_probe.cpp)
    target_link_libraries(noul_wording_probe PRIVATE chuse_decision)

    add_executable(offset_probe tools/offset_probe.cpp)
    target_link_libraries(offset_probe PRIVATE chuse_decision)

    add_executable(signal_probe tools/signal_probe.cpp)
    target_link_libraries(signal_probe PRIVATE chuse_decision)

    add_executable(noul_bias_probe tools/noul_bias_probe.cpp)
    target_link_libraries(noul_bias_probe PRIVATE chuse_decision)
endif()