#pragma once

#include <cstddef>

extern "C" {

void* cadence_create_session(int exercise);
void cadence_destroy_session(void* session);
void cadence_reset_session(void* session);

// landmarks contains count * 4 floats in x, y, z, visibility order.
// The returned JSON remains valid until the next call on the same thread.
const char* cadence_process_landmarks(void* session,
                                      const float* landmarks,
                                      std::size_t count,
                                      float elapsed_seconds);

const char* cadence_session_summary(void* session);

}
