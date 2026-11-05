package com.campus.sport

enum class ExerciseType {
    REPS,
    HOLD,
}

/**
 * The five exercises implemented by the C++ core.
 * The order must match `res/values/arrays.xml` and the WASM/JNI exercise ids.
 */
enum class CadenceExercise(
    val id: Int,
    val displayName: String,
    val type: ExerciseType,
    val mainLabel: String,
    val cameraHint: String,
    val caloriesPerRep: Float = 0f,
    val caloriesPerSecond: Float = 0f,
) {
    SQUAT(
        0, "深蹲", ExerciseType.REPS, "膝角",
        "侧向机位，左侧身体朝向镜头，保证髋、膝、踝可见。",
        caloriesPerRep = 0.6f,
    ),
    PUSHUP(
        1, "俯卧撑", ExerciseType.REPS, "肘角",
        "侧向机位，左侧身体朝向镜头，保证肩、肘、腕、髋、膝可见。",
        caloriesPerRep = 0.5f,
    ),
    LUNGE(
        2, "弓步蹲", ExerciseType.REPS, "前膝角",
        "侧向机位，左侧身体朝向镜头，保证髋、膝、踝可见。",
        caloriesPerRep = 0.7f,
    ),
    PLANK(
        3, "平板支撑", ExerciseType.HOLD, "髋角",
        "侧向机位，左侧身体朝向镜头，保证肩、髋、膝、踝可见。",
        caloriesPerSecond = 0.15f,
    ),
    JUMPING_JACK(
        4, "开合跳", ExerciseType.REPS, "腕距",
        "正面机位，保证肩、肘、腕、髋、膝、踝可见。",
        caloriesPerRep = 0.4f,
    ),
}
