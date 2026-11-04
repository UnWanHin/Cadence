#pragma once

#include "types.h"

#include <istream>
#include <memory>

namespace cadence {

class PoseSource {
 public:
  virtual ~PoseSource() = default;
  virtual bool next(PoseFrame& frame) = 0;
};

class TextPoseSource final : public PoseSource {
 public:
  explicit TextPoseSource(std::istream& input) : input_(input) {}
  bool next(PoseFrame& frame) override;

 private:
  std::istream& input_;
};

class PoseFrameProcessor {
 public:
  explicit PoseFrameProcessor(std::unique_ptr<PoseSource> source)
      : source_(std::move(source)) {}
  template <typename Callback>
  int process_all(Callback callback) {
    PoseFrame frame;
    int count = 0;
    while (source_->next(frame)) {
      callback(frame);
      ++count;
    }
    return count;
  }

 private:
  std::unique_ptr<PoseSource> source_;
};

}
