#include "geometry.h"
#include <algorithm>
#include <cmath>
namespace cadence {
float angle_at(const Landmark& a, const Landmark& b, const Landmark& c) {
  const float abx=a.x-b.x, aby=a.y-b.y, abz=a.z-b.z;
  const float cbx=c.x-b.x, cby=c.y-b.y, cbz=c.z-b.z;
  const float dot=abx*cbx+aby*cby+abz*cbz;
  const float na=std::sqrt(abx*abx+aby*aby+abz*abz), nc=std::sqrt(cbx*cbx+cby*cby+cbz*cbz);
  if (na == 0 || nc == 0) return 0;
  constexpr float pi = 3.14159265358979323846f;
  return std::acos(std::clamp(dot / (na * nc), -1.0f, 1.0f)) * 180.0f / pi;
}
float distance_between(const Landmark& a, const Landmark& b) {
  return std::hypot(a.x-b.x, a.y-b.y);
}
}
