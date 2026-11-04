#include "repetition_counter.h"
namespace cadence {
CounterEvent RepetitionCounter::update(float progress) {
  CounterEvent event;
  if (!in_target_ && progress >= thresholds_.enter) { in_target_=true; event.entered_target=true; }
  else if (in_target_ && progress <= thresholds_.exit) { in_target_=false; ++count_; event.repetition_completed=true; }
  return event;
}
void RepetitionCounter::reset() { in_target_=false; count_=0; }
}
