cmake_minimum_required(VERSION 3.22.1)
project(cadence_jni LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Android, WASM, and desktop tests all compile the same canonical C++ core.
# Do not copy movement rules into Kotlin.
set(CADENCE_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../src")
set(CADENCE_SRC "${CADENCE_ROOT}/cadence")

add_library(cadence SHARED
    jni_bridge.cpp
    ${CADENCE_SRC}/geometry.cpp
    ${CADENCE_SRC}/pose_features.cpp
    ${CADENCE_SRC}/exercise_rules.cpp
    ${CADENCE_SRC}/repetition_counter.cpp
    ${CADENCE_SRC}/workout_session.cpp
    ${CADENCE_SRC}/wasm_api.cpp
)

target_include_directories(cadence PRIVATE
    ${CADENCE_ROOT}
    ${CADENCE_SRC}
)

target_compile_options(cadence PRIVATE -Wall -Wextra -Wpedantic)
