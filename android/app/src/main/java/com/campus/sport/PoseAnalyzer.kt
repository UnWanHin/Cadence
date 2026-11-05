package com.campus.sport

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker

class PoseAnalyzer(
    private val poseLandmarker: PoseLandmarker,
    private val onResult: (List<NormalizedLandmark>, Int, Int) -> Unit,
    private val onError: (Throwable) -> Unit,
) : ImageAnalysis.Analyzer {

    override fun analyze(image: ImageProxy) {
        try {
            val source = image.toBitmap()
            val rotation = image.imageInfo.rotationDegrees
            val bitmap: Bitmap = if (rotation == 0) {
                source
            } else {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
            }
            val mpImage = BitmapImageBuilder(bitmap).build()
            val timestampMs = image.imageInfo.timestamp / 1_000_000
            val result = poseLandmarker.detectForVideo(mpImage, timestampMs)
            val landmarks = result.landmarks().firstOrNull().orEmpty()
            onResult(landmarks, bitmap.width, bitmap.height)
        } catch (error: Throwable) {
            onError(error)
        } finally {
            image.close()
        }
    }
}
