# Emscripten toolchain fragment for the browser-facing C++ core.
# Configure with: emcmake cmake -S src -B build-wasm -DCADENCE_BUILD_WASM=ON

add_executable(cadence_core_wasm
  cadence/geometry.cpp
  cadence/pose_features.cpp
  cadence/exercise_rules.cpp
  cadence/repetition_counter.cpp
  cadence/workout_session.cpp
  cadence/wasm_api.cpp
)
target_include_directories(cadence_core_wasm PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
set_target_properties(cadence_core_wasm PROPERTIES
  OUTPUT_NAME "cadence-core"
  SUFFIX ".js"
)
target_link_options(cadence_core_wasm PRIVATE
  "-sMODULARIZE=1"
  "-sEXPORT_ES6=1"
  "-sENVIRONMENT=web"
  "-sALLOW_MEMORY_GROWTH=1"
  "-sEXPORTED_FUNCTIONS=['_malloc','_free','_cadence_create_session','_cadence_destroy_session','_cadence_reset_session','_cadence_process_landmarks','_cadence_session_summary']"
  "-sEXPORTED_RUNTIME_METHODS=['UTF8ToString']"
)
