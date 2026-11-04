cmake_minimum_required(VERSION 3.16)
project(cadence_core LANGUAGES CXX)

option(CADENCE_BUILD_WASM "Build the C++ movement core for browser WebAssembly" OFF)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

include(GNUInstallDirs)

add_library(cadence_core
  cadence/geometry.cpp
  cadence/pose_features.cpp
  cadence/pose_source.cpp
  cadence/pose_inference.cpp
  cadence/pose_backend.cpp
  cadence/wasm_api.cpp
  cadence/exercise_rules.cpp
  cadence/repetition_counter.cpp
  cadence/workout_session.cpp
)
target_include_directories(cadence_core PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_options(cadence_core PRIVATE -Wall -Wextra -Wpedantic)

add_executable(cadence_demo app/main.cpp)
target_link_libraries(cadence_demo PRIVATE cadence_core)

enable_testing()
add_executable(cadence_tests ../tests/cadence_tests.cpp)
target_link_libraries(cadence_tests PRIVATE cadence_core)
target_compile_options(cadence_tests PRIVATE -Wall -Wextra -Wpedantic)
add_test(NAME cadence_tests COMMAND cadence_tests)

add_executable(cadence_wasm_api_test cadence/wasm_api_test.cpp)
target_link_libraries(cadence_wasm_api_test PRIVATE cadence_core)
target_compile_options(cadence_wasm_api_test PRIVATE -Wall -Wextra -Wpedantic)
add_test(NAME cadence_wasm_api_test COMMAND cadence_wasm_api_test)

if(CADENCE_BUILD_WASM)
  include(${CMAKE_CURRENT_SOURCE_DIR}/wasm.cmake)
endif()

add_library(cadence_wasm OBJECT cadence/wasm_api.cpp)
target_link_libraries(cadence_wasm PRIVATE cadence_core)
target_include_directories(cadence_wasm PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
