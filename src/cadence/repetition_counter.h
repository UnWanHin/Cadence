#pragma once
#include "types.h"
namespace cadence {
class RepetitionCounter {
 public:
  explicit RepetitionCounter(CounterThresholds thresholds) : thresholds_(thresholds) {}
  CounterEvent update(float progress);
  void reset();
  int count() const { return count_; }
 private:
  CounterThresholds thresholds_;
  bool in_target_{false};
  int count_{0};
};
}
