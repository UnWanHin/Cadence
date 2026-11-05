// Exercise metadata and a JavaScript reference mirror used by tests.
// The browser UI evaluates poses through cadence-wasm-bridge.js, which calls
// the C++ core compiled to WebAssembly. This file is not the runtime evaluator.
// Canonical source: src/cadence/* (see docs/canonical-rules.md).
const Core = (() => {
  const KP = {
    L_SHO: 11, R_SHO: 12, L_ELB: 13, R_ELB: 14, L_WRI: 15, R_WRI: 16,
    L_HIP: 23, R_HIP: 24, L_KNE: 25, R_KNE: 26, L_ANK: 27, R_ANK: 28,
  };
  const VISIBILITY = 0.5;

  const EXERCISES = {
    squat: {
      id: 0, code: 'squat', name: '深蹲', type: 'reps', mainLabel: '膝角', mainUnit: '°',
      caloriesPerRep: 0.6, description: '双脚与肩同宽，下蹲至膝角不超过 90°，再站起回位。',
      cameraHint: '侧向机位，左侧身体朝向镜头，保证髋、膝、踝可见。',
    },
    pushup: {
      id: 1, code: 'pushup', name: '俯卧撑', type: 'reps', mainLabel: '肘角', mainUnit: '°',
      caloriesPerRep: 0.5, description: '身体保持直线，下降至肘角不超过 90°，再撑起。',
      cameraHint: '侧向机位，左侧身体朝向镜头，保证肩、肘、腕、髋、膝可见。',
    },
    lunge: {
      id: 2, code: 'lunge', name: '弓步蹲', type: 'reps', mainLabel: '前膝角', mainUnit: '°',
      caloriesPerRep: 0.7, description: '前后分腿，下蹲至前膝角不超过 90°，再回位。',
      cameraHint: '侧向机位，左侧身体朝向镜头，保证髋、膝、踝可见。',
    },
    plank: {
      id: 3, code: 'plank', name: '平板支撑', type: 'hold', mainLabel: '髋角', mainUnit: '°',
      caloriesPerSecond: 0.15, description: '以前臂和脚尖支撑，肩、髋、膝、踝保持一条直线。',
      cameraHint: '侧向机位，左侧身体朝向镜头，保证肩、髋、膝、踝可见。',
    },
    jumpingjack: {
      id: 4, code: 'jumpingjack', name: '开合跳', type: 'reps', mainLabel: '腕距', mainUnit: '',
      caloriesPerRep: 0.4, description: '双臂展开至腕距不小于 0.70，再并拢回位。',
      cameraHint: '正面机位，保证肩、肘、腕、髋、膝、踝可见。',
    },
  };
  const ORDER = ['squat', 'pushup', 'lunge', 'plank', 'jumpingjack'];

  function angleAt(a, b, c) {
    const abx = a.x - b.x, aby = a.y - b.y, abz = (a.z || 0) - (b.z || 0);
    const cbx = c.x - b.x, cby = c.y - b.y, cbz = (c.z || 0) - (b.z || 0);
    const dot = abx * cbx + aby * cby + abz * cbz;
    const na = Math.hypot(abx, aby, abz);
    const nc = Math.hypot(cbx, cby, cbz);
    if (na === 0 || nc === 0) return 0;
    const cos = Math.max(-1, Math.min(1, dot / (na * nc)));
    return (Math.acos(cos) * 180) / Math.PI;
  }

  function distance(a, b) {
    return Math.hypot(a.x - b.x, a.y - b.y);
  }

  function visible(landmarks, index) {
    const p = landmarks && landmarks[index];
    return !!p && (p.visibility == null ? 1 : p.visibility) >= VISIBILITY;
  }

  function jointAngle(landmarks, a, b, c) {
    if (!visible(landmarks, a) || !visible(landmarks, b) || !visible(landmarks, c)) return null;
    return angleAt(landmarks[a], landmarks[b], landmarks[c]);
  }

  function minOptional(a, b) {
    if (a == null && b == null) return null;
    if (a == null) return b;
    if (b == null) return a;
    return Math.min(a, b);
  }

  function extractFeatures(landmarks) {
    const leftKnee = jointAngle(landmarks, KP.L_HIP, KP.L_KNE, KP.L_ANK);
    const rightKnee = jointAngle(landmarks, KP.R_HIP, KP.R_KNE, KP.R_ANK);
    const leftElbow = jointAngle(landmarks, KP.L_SHO, KP.L_ELB, KP.L_WRI);
    const rightElbow = jointAngle(landmarks, KP.R_SHO, KP.R_ELB, KP.R_WRI);
    const hip = jointAngle(landmarks, KP.L_SHO, KP.L_HIP, KP.L_KNE);
    const wristDistance = visible(landmarks, KP.L_WRI) && visible(landmarks, KP.R_WRI)
      ? distance(landmarks[KP.L_WRI], landmarks[KP.R_WRI]) : null;
    return {
      leftKnee, rightKnee, knee: minOptional(leftKnee, rightKnee),
      leftElbow, rightElbow, elbow: minOptional(leftElbow, rightElbow),
      hip, wristDistance,
    };
  }

  function evaluateFeatures(code, features) {
    const exercise = EXERCISES[code];
    const result = {
      poseDetected: false, inTarget: false, repetitionCompleted: false,
      isHold: exercise ? exercise.type === 'hold' : false,
      mainValue: null, quality: 0, feedback: [],
    };
    if (!exercise) {
      result.feedback.push('未知动作');
      return result;
    }

    if (code === 'plank') {
      if (features.hip == null) return result;
      result.poseDetected = true;
      result.mainValue = features.hip;
      result.quality = Math.max(0, 1 - Math.abs(features.hip - 180) / 60);
      result.inTarget = result.quality >= 0.65;
      if (result.quality < 0.65) result.feedback.push('保持身体直线，收紧核心');
      return result;
    }

    if (code === 'jumpingjack') {
      if (features.wristDistance == null) return result;
      result.poseDetected = true;
      result.mainValue = features.wristDistance;
      result.inTarget = features.wristDistance >= 0.7;
      result.quality = result.inTarget ? 1 : 0.7;
      return result;
    }

    if (code === 'pushup') {
      if (features.elbow == null) return result;
      result.poseDetected = true;
      result.mainValue = features.elbow;
      result.inTarget = features.elbow <= 90;
      result.quality = 1;
      if (features.hip != null && (features.hip < 150 || features.hip > 200)) {
        result.quality = 0.7;
        result.feedback.push('保持身体直线，避免塌腰或翘臀');
      }
      return result;
    }

    // squat / lunge
    if (features.knee == null) return result;
    result.poseDetected = true;
    result.mainValue = features.knee;
    result.inTarget = features.knee <= 90;
    result.quality = 1;
    if (features.leftKnee != null && features.rightKnee != null
      && Math.abs(features.leftKnee - features.rightKnee) > 20) {
      result.quality = 0.7;
      result.feedback.push('左右膝屈度不一致，调整重心');
    }
    if (features.hip != null && features.hip < 60) {
      result.quality = Math.min(result.quality, 0.7);
      result.feedback.push('躯干前倾过多，注意挺胸');
    }
    return result;
  }

  function progressFor(code, features, result) {
    if (code === 'jumpingjack') return result.inTarget ? 1 : 0;
    if (!result.poseDetected || result.mainValue == null) return 0;
    if (result.mainValue <= 90) return 1;
    if (result.mainValue >= 160) return 0;
    return (160 - result.mainValue) / 70;
  }

  class RepCounter {
    constructor(enter = 0.75, exit = 0.55) {
      this.enter = enter;
      this.exit = exit;
      this.inTarget = false;
      this.count = 0;
    }
    update(progress) {
      const event = { enteredTarget: false, repetitionCompleted: false };
      if (!this.inTarget && progress >= this.enter) {
        this.inTarget = true;
        event.enteredTarget = true;
      } else if (this.inTarget && progress <= this.exit) {
        this.inTarget = false;
        this.count += 1;
        event.repetitionCompleted = true;
      }
      return event;
    }
    reset() {
      this.inTarget = false;
      this.count = 0;
    }
  }

  class WorkoutSession {
    constructor(code) {
      if (!EXERCISES[code]) throw new Error('未知动作: ' + code);
      this.code = code;
      this.reset();
    }

    reset() {
      this.counter = new RepCounter();
      this.points = 0;
      this.calories = 0;
      this.qualitySum = 0;
      this.qualitySamples = 0;
      this.elapsedSeconds = 0;
      this.lastResult = null;
    }

    process(landmarks, elapsedSeconds) {
      const exercise = EXERCISES[this.code];
      const features = extractFeatures(landmarks || []);
      const result = evaluateFeatures(this.code, features);
      this.elapsedSeconds = Math.max(0, Number(elapsedSeconds) || 0);
      this.lastResult = result;
      if (!result.poseDetected) return result;

      this.qualitySum += result.quality;
      this.qualitySamples += 1;

      if (exercise.type === 'hold') {
        this.calories = this.elapsedSeconds * (exercise.caloriesPerSecond || 0);
        this.points = Math.floor(this.calories * 10);
        return result;
      }

      const event = this.counter.update(progressFor(this.code, features, result));
      if (event.repetitionCompleted) {
        this.points += Math.floor(10 * result.quality);
        this.calories += exercise.caloriesPerRep || 0;
        result.repetitionCompleted = true;
      }
      return result;
    }

    summary() {
      return {
        repetitions: this.counter.count,
        points: this.points,
        calories: this.calories,
        averageQuality: this.qualitySamples ? this.qualitySum / this.qualitySamples : 0,
        elapsedSeconds: this.elapsedSeconds,
      };
    }
  }

  function day(timestamp) {
    return new Date(timestamp + 28800000).toISOString().slice(0, 10);
  }

  function week(timestamp) {
    const d = new Date(day(timestamp) + 'T00:00:00Z');
    d.setUTCDate(d.getUTCDate() - ((d.getUTCDay() + 6) % 7));
    return d.toISOString().slice(0, 10);
  }

  function formatDuration(ms) {
    const total = Math.max(0, Math.floor(ms / 1000));
    const minutes = Math.floor(total / 60);
    const seconds = total % 60;
    return minutes + ':' + String(seconds).padStart(2, '0');
  }

  return {
    KP, VISIBILITY, EXERCISES, ORDER,
    angleAt, distance, extractFeatures, evaluateFeatures, progressFor,
    RepCounter, WorkoutSession, day, week, formatDuration,
  };
})();
if (typeof module !== 'undefined') module.exports = Core;
