cmake_minimum_required(VERSION 3.22.1)

project("llamaandroid")

# Android JNI library
add_library(${CMAKE_PROJECT_NAME} SHARED
        native-lib.cpp
        )

# llama.cpp libraries
set(JNI_LIBS_DIR
        ${CMAKE_SOURCE_DIR}/../jniLibs/arm64-v8a
        )

add_library(llama SHARED IMPORTED)
set_target_properties(llama PROPERTIES
        IMPORTED_LOCATION
        ${JNI_LIBS_DIR}/libllama.so
        )

add_library(ggml SHARED IMPORTED)
set_target_properties(ggml PROPERTIES
        IMPORTED_LOCATION
        ${JNI_LIBS_DIR}/libggml.so
        )

add_library(ggml-base SHARED IMPORTED)
set_target_properties(ggml-base PROPERTIES
        IMPORTED_LOCATION
        ${JNI_LIBS_DIR}/libggml-base.so
        )

add_library(ggml-cpu SHARED IMPORTED)
set_target_properties(ggml-cpu PROPERTIES
        IMPORTED_LOCATION
        ${JNI_LIBS_DIR}/libggml-cpu.so
        )

add_library(omp SHARED IMPORTED)
set_target_properties(omp PROPERTIES
        IMPORTED_LOCATION
        ${JNI_LIBS_DIR}/libomp.so
        )

# Include llama.h
target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        )

# Link
target_link_libraries(${CMAKE_PROJECT_NAME}
        llama
        ggml
        ggml-base
        ggml-cpu
        omp
        android
        log
        )