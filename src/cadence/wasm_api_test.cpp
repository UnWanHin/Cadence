#include "wasm_api.h"

#include <cmath>
#include <iostream>
#include <stdexcept>

namespace {
void expect(bool condition, const char* message) {
  if (!condition) throw std::runtime_error(message);
}
}

int main() {
  try {
    void* session = cadence_create_session(0);
    float landmarks[33 * 4]{};
    for (int i = 0; i < 33; ++i) landmarks[i * 4 + 3] = 1.0f;
    const char* result = cadence_process_landmarks(session, landmarks, 33, 1.0f);
    expect(result != nullptr, "WASM API should return JSON result");
    expect(std::string(result).find("poseDetected") != std::string::npos,
           "WASM API result should include pose state");
    const char* summary = cadence_session_summary(session);
    expect(std::string(summary).find("repetitions") != std::string::npos,
           "WASM API summary should include repetitions");
    cadence_destroy_session(session);
    std::cout << "wasm_api_test: passed\n";
  } catch (const std::exception& error) {
    std::cerr << "wasm_api_test: " << error.what() << '\n';
    return 1;
  }
  return 0;
}
