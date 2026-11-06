// Landmark smoothing and body selection for the room page.
//
// The prototype behaved better than the page in two visible ways, and both come
// from this file:
//
//   1. Raw landmarks jitter frame to frame, so the drawn skeleton twitches and
//      the joint angle wobbles around whatever threshold it is near. An
//      exponential moving average fixes both.
//   2. When the pose model finds two bodies, the page used to throw the frame
//      away and print "需要单人入镜". That spammed the message and punched gaps
//      into the angle series the rep counter reads. Picking the body that is
//      closest to the previous frame keeps the series continuous.
//
// Kept free of DOM access so it can be tested in plain node.

/** Exponential moving average weight for each new frame. */
export const DEFAULT_ALPHA = 0.55;

function copyOf(point) {
  return {
    x: point.x,
    y: point.y,
    z: point.z || 0,
    visibility: point.visibility == null ? 1 : point.visibility,
  };
}

function visibilitySum(pose) {
  let total = 0;
  for (const point of pose) total += point.visibility == null ? 1 : point.visibility;
  return total;
}

export function createPoseSmoother(alpha = DEFAULT_ALPHA) {
  if (!(alpha > 0 && alpha <= 1)) throw new Error('alpha 必须在 (0, 1] 之间');
  let smoothed = null;

  function reset() {
    smoothed = null;
  }

  /** Mean landmark distance to the previous frame; Infinity when there is none. */
  function distanceToPrevious(pose) {
    if (!smoothed || smoothed.length !== pose.length) return Infinity;
    let total = 0;
    for (let i = 0; i < pose.length; i += 1) {
      total += Math.abs(pose[i].x - smoothed[i].x) + Math.abs(pose[i].y - smoothed[i].y);
    }
    return total / pose.length;
  }

  /**
   * Chooses the body to follow out of [poses].
   * Returns the pose plus whether the frame was ambiguous (more than one body).
   */
  function pick(poses) {
    if (!Array.isArray(poses) || poses.length === 0) return null;
    if (poses.length === 1) return { pose: poses[0], others: 0 };
    let best = poses[0];
    let bestScore = distanceToPrevious(poses[0]);
    if (!Number.isFinite(bestScore)) {
      // Nothing tracked yet: start with the best-visible body.
      bestScore = -visibilitySum(poses[0]);
      for (const pose of poses.slice(1)) {
        const score = -visibilitySum(pose);
        if (score < bestScore) { bestScore = score; best = pose; }
      }
    } else {
      for (const pose of poses.slice(1)) {
        const score = distanceToPrevious(pose);
        if (score < bestScore) { bestScore = score; best = pose; }
      }
    }
    return { pose: best, others: poses.length - 1 };
  }

  /** EMA of [pose]; returns the same array instance, updated in place. */
  function smooth(pose) {
    if (!smoothed || smoothed.length !== pose.length) {
      smoothed = pose.map(copyOf);
      return smoothed;
    }
    for (let i = 0; i < pose.length; i += 1) {
      const current = smoothed[i];
      const next = pose[i];
      current.x += (next.x - current.x) * alpha;
      current.y += (next.y - current.y) * alpha;
      current.z += ((next.z || 0) - current.z) * alpha;
      current.visibility = next.visibility == null ? 1 : next.visibility;
    }
    return smoothed;
  }

  return { pick, smooth, reset, distanceToPrevious, get current() { return smoothed; } };
}
