package com.campus.sport

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import org.json.JSONObject

data class FrameResult(
    val poseDetected: Boolean = false,
    val inTarget: Boolean = false,
    val repetitionCompleted: Boolean = false,
    val hold: Boolean = false,
    val mainValue: Float? = null,
    val quality: Float = 0f,
    val feedback: List<String> = emptyList(),
)

data class SessionSummary(
    val repetitions: Int = 0,
    val points: Int = 0,
    val calories: Float = 0f,
    val averageQuality: Float = 0f,
    val elapsedSeconds: Float = 0f,
)

/**
 * The Android UI only depends on this interface.
 *
 * The production implementation is [NativeCadenceEngine], which calls the
 * canonical C++ core through JNI. Keeping this interface makes it possible to
 * add a test double without changing the activity or camera code.
 */
interface CadenceEngine : AutoCloseable {
    fun start(exercise: CadenceExercise)
    fun process(landmarks: List<NormalizedLandmark>, elapsedSeconds: Float): FrameResult
    fun summary(): SessionSummary
}

/**
 * JNI bridge to the repository-level `src/cadence` C++ core.
 *
 * The native library `libcadence.so` is built by
 * `app/src/main/cpp/CMakeLists.txt`; it compiles the C++ core directly, so the
 * APK does not maintain a second copy of the movement rules.
 */
class NativeCadenceEngine : CadenceEngine {
    private var handle: Long = 0L

    init {
        System.loadLibrary("cadence")
    }

    @Synchronized
    override fun start(exercise: CadenceExercise) {
        close()
        handle = nativeCreate(exercise.id)
        check(handle != 0L) { "C++ 模型创建会话失败" }
    }

    @Synchronized
    override fun process(
        landmarks: List<NormalizedLandmark>,
        elapsedSeconds: Float,
    ): FrameResult {
        if (handle == 0L) return FrameResult()
        val buffer = FloatArray(33 * 4)
        for (index in 0 until 33) {
            val point = landmarks.getOrNull(index)
            if (point == null) {
                buffer[index * 4 + 3] = 0f
            } else {
                buffer[index * 4] = point.x()
                buffer[index * 4 + 1] = point.y()
                buffer[index * 4 + 2] = point.z()
                buffer[index * 4 + 3] = if (point.visibility().isPresent) {
                    point.visibility().get()
                } else {
                    0f
                }
            }
        }

        val payload = nativeProcess(handle, buffer, elapsedSeconds)
        val json = runCatching { JSONObject(payload) }.getOrElse { return FrameResult() }
        if (json.has("error")) return FrameResult()

        val feedback = mutableListOf<String>()
        json.optJSONArray("feedback")?.let { array ->
            for (index in 0 until array.length()) {
                feedback += array.optString(index)
            }
        }
        val poseDetected = json.optBoolean("poseDetected", false)
        return FrameResult(
            poseDetected = poseDetected,
            inTarget = json.optBoolean("inTarget", false),
            repetitionCompleted = json.optBoolean("repetitionCompleted", false),
            hold = json.optBoolean("hold", false),
            mainValue = if (poseDetected) json.optDouble("mainValue", 0.0).toFloat() else null,
            quality = json.optDouble("quality", 0.0).toFloat(),
            feedback = feedback,
        )
    }

    @Synchronized
    override fun summary(): SessionSummary {
        if (handle == 0L) return SessionSummary()
        val payload = nativeSummary(handle)
        val json = runCatching { JSONObject(payload) }.getOrElse { return SessionSummary() }
        return SessionSummary(
            repetitions = json.optInt("repetitions", 0),
            points = json.optInt("points", 0),
            calories = json.optDouble("calories", 0.0).toFloat(),
            averageQuality = json.optDouble("averageQuality", 0.0).toFloat(),
            elapsedSeconds = json.optDouble("elapsedSeconds", 0.0).toFloat(),
        )
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    private external fun nativeCreate(exerciseId: Int): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeProcess(
        handle: Long,
        landmarks: FloatArray,
        elapsedSeconds: Float,
    ): String
    private external fun nativeSummary(handle: Long): String
}
