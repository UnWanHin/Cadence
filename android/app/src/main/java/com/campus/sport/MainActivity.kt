package com.campus.sport

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Size
import android.view.View
import android.widget.AdapterView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.campus.sport.lan.LanRoom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var videoFrameView: ImageView
    private lateinit var overlayView: PoseOverlayView
    private lateinit var exerciseSpinner: Spinner
    private lateinit var startButton: MaterialButton
    private lateinit var stopButton: MaterialButton
    private lateinit var videoButton: MaterialButton
    private lateinit var statusText: TextView
    private lateinit var videoProgressText: TextView
    private lateinit var cameraHintText: TextView
    private lateinit var flashButton: ImageButton
    private lateinit var flipButton: ImageButton
    private lateinit var metricReps: TextView
    private lateinit var metricRepsLabel: TextView
    private lateinit var metricQuality: TextView
    private lateinit var metricPoints: TextView
    private lateinit var metricCalories: TextView
    private lateinit var metricActive: TextView
    private lateinit var metricNatural: TextView

    private val engine: CadenceEngine = NativeCadenceEngine()
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var poseLandmarker: PoseLandmarker? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var camera: Camera? = null
    private var useFrontCamera = false
    private var isTorchOn = false
    private var hasFrontCamera = false
    private var hasBackCamera = false
    private var trainingSource = TrainingSource.CAMERA
    private var lastDisplayedVideoFrame: Bitmap? = null
    @Volatile
    private var videoAnalysisToken = 0
    private var videoProgressMs = 0L
    @Volatile
    private var isRunning = false
    private var sessionStartMs = 0L
    private var currentExercise = CadenceExercise.SQUAT

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startTraining()
        } else {
            statusText.text = getString(R.string.status_no_permission)
        }
    }

    private val videoPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, R.string.video_permission_denied, Toast.LENGTH_LONG).show()
        }
        openVideoPicker()
    }

    private val videoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) {
            statusText.text = getString(R.string.video_pick_cancelled)
        } else {
            startTraining(TrainingSource.VIDEO, uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER

        exerciseSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                selectExercise(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        startButton.setOnClickListener {
            if (isRunning) stopTraining() else ensurePermissionAndStart()
        }
        stopButton.setOnClickListener { stopTraining() }
        videoButton.setOnClickListener { pickVideo() }
        flashButton.setOnClickListener { toggleFlash() }
        flipButton.setOnClickListener { flipCamera() }

        selectExercise(0)
        resetMetrics()
        updateCameraControls()
    }

    private fun bindViews() {
        previewView = findViewById(R.id.previewView)
        videoFrameView = findViewById(R.id.videoFrameView)
        overlayView = findViewById(R.id.overlayView)
        exerciseSpinner = findViewById(R.id.exerciseSpinner)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        videoButton = findViewById(R.id.videoButton)
        statusText = findViewById(R.id.statusText)
        videoProgressText = findViewById(R.id.videoProgressText)
        cameraHintText = findViewById(R.id.cameraHintText)
        flashButton = findViewById(R.id.flashButton)
        flipButton = findViewById(R.id.flipButton)
        metricReps = findViewById(R.id.metricReps)
        metricRepsLabel = findViewById(R.id.metricRepsLabel)
        metricQuality = findViewById(R.id.metricQuality)
        metricPoints = findViewById(R.id.metricPoints)
        metricCalories = findViewById(R.id.metricCalories)
        metricActive = findViewById(R.id.metricActive)
        metricNatural = findViewById(R.id.metricNatural)
    }

    private fun selectExercise(position: Int) {
        currentExercise = CadenceExercise.values().getOrElse(position) { CadenceExercise.SQUAT }
        cameraHintText.text = currentExercise.cameraHint
        metricRepsLabel.setText(
            if (currentExercise.type == ExerciseType.HOLD) R.string.metric_hold
            else R.string.metric_reps,
        )
        if (!isRunning) resetMetrics()
    }

    private fun ensurePermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startTraining()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun pickVideo() {
        if (isRunning) return
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(this, permission)
            == PackageManager.PERMISSION_GRANTED
        ) {
            openVideoPicker()
        } else {
            statusText.text = getString(R.string.video_permission_rationale)
            videoPermissionLauncher.launch(permission)
        }
    }

    private fun openVideoPicker() {
        if (!isRunning) videoPickerLauncher.launch("video/*")
    }

    private fun startTraining(
        source: TrainingSource = TrainingSource.CAMERA,
        videoUri: Uri? = null,
    ) {
        if (isRunning) return
        if (source == TrainingSource.VIDEO && videoUri == null) {
            statusText.text = "没有选择视频"
            return
        }
        currentExercise = CadenceExercise.values().getOrElse(exerciseSpinner.selectedItemPosition) {
            CadenceExercise.SQUAT
        }
        try {
            engine.start(currentExercise)
        } catch (error: Throwable) {
            statusText.text = "无法创建 C++ 会话：" + (error.message ?: "")
            return
        }
        trainingSource = source
        videoProgressMs = 0L
        isRunning = true
        sessionStartMs = SystemClock.elapsedRealtime()
        exerciseSpinner.isEnabled = false
        startButton.isEnabled = false
        stopButton.isEnabled = true
        statusText.text = getString(
            if (source == TrainingSource.VIDEO) R.string.status_video_loading
            else R.string.status_loading,
        )
        cameraHintText.text = currentExercise.cameraHint
        videoProgressText.visibility = View.GONE
        overlayView.clear()
        resetMetrics()
        updateCameraControls()

        lifecycleScope.launch {
            try {
                val landmarker = withContext(Dispatchers.Default) { createPoseLandmarker() }
                withContext(Dispatchers.Main) {
                    if (!isRunning) {
                        landmarker.close()
                        return@withContext
                    }
                    poseLandmarker = landmarker
                    if (source == TrainingSource.VIDEO && videoUri != null) {
                        startVideoAnalysis(videoUri, landmarker)
                    } else {
                        startCamera(landmarker)
                    }
                }
            } catch (error: Throwable) {
                withContext(Dispatchers.Main) {
                    stopTraining()
                    statusText.text = getString(R.string.status_model_error) +
                        ": " + (error.message ?: "")
                }
            }
        }
    }

    private fun createPoseLandmarker(): PoseLandmarker {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("pose_landmarker_full.task")
            .build()
        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.VIDEO)
            .setNumPoses(1)
            .setMinPoseDetectionConfidence(0.5f)
            .setMinPosePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .build()
        return PoseLandmarker.createFromOptions(applicationContext, options)
    }

    private fun startCamera(landmarker: PoseLandmarker) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                if (!isRunning) return@addListener

                previewView.visibility = View.VISIBLE
                videoFrameView.visibility = View.GONE
                videoProgressText.visibility = View.GONE

                preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                imageAnalysis = analysis
                analysis.setAnalyzer(
                    cameraExecutor,
                    PoseAnalyzer(
                        poseLandmarker = landmarker,
                        onResult = { landmarks, imageWidth, imageHeight ->
                            onPoseResult(landmarks, imageWidth, imageHeight)
                        },
                        onError = { error ->
                            runOnUiThread {
                                if (isRunning) {
                                    statusText.text = "识别错误：" + (error.message ?: "未知")
                                }
                            }
                        },
                    ),
                )

                bindCamera(provider)
            } catch (error: Throwable) {
                stopTraining()
                statusText.text = getString(R.string.status_model_error) +
                    ": " + (error.message ?: "")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startVideoAnalysis(uri: Uri, landmarker: PoseLandmarker) {
        cameraProvider?.unbindAll()
        imageAnalysis?.clearAnalyzer()
        imageAnalysis = null
        preview = null
        camera = null
        isTorchOn = false
        previewView.visibility = View.GONE
        videoFrameView.visibility = View.VISIBLE
        videoProgressText.visibility = View.VISIBLE
        videoProgressText.text = getString(R.string.status_video_progress, 0)
        overlayView.setMirrorHorizontally(false)
        overlayView.clear()
        updateCameraControls()

        val token = ++videoAnalysisToken
        cameraExecutor.execute {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(applicationContext, uri)
                val durationMs = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                if (durationMs <= 0L) throw IllegalStateException("无法读取视频时长")
                val rotation = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull() ?: 0

                var positionMs = 0L
                val endMs = minOf(durationMs, MAX_VIDEO_ANALYSIS_MS)
                while (isRunning && token == videoAnalysisToken && positionMs <= endMs) {
                    val frame = extractVideoFrame(retriever, positionMs, rotation)
                    if (frame != null) {
                        val mpImage = BitmapImageBuilder(frame).build()
                        val result = landmarker.detectForVideo(mpImage, positionMs)
                        val landmarks = result.landmarks().firstOrNull().orEmpty()
                        val framePosition = positionMs
                        runOnUiThread {
                            if (!isRunning || token != videoAnalysisToken) {
                                frame.recycle()
                                return@runOnUiThread
                            }
                            videoProgressMs = framePosition
                            showVideoFrame(frame)
                            val percent = if (durationMs > 0L) {
                                ((framePosition * 100) / durationMs).toInt().coerceIn(0, 100)
                            } else {
                                0
                            }
                            videoProgressText.text = getString(R.string.status_video_progress, percent)
                            if (landmarks.isEmpty()) {
                                statusText.text = getString(R.string.status_video_no_pose)
                            } else {
                                onPoseResult(
                                    landmarks,
                                    frame.width,
                                    frame.height,
                                    framePosition / 1000f,
                                )
                            }
                        }
                    }
                    positionMs += VIDEO_FRAME_INTERVAL_MS
                }

                if (isRunning && token == videoAnalysisToken) {
                    runOnUiThread {
                        if (isRunning && token == videoAnalysisToken) {
                            videoProgressMs = durationMs
                            videoProgressText.text = getString(R.string.status_video_progress, 100)
                            stopTraining()
                        }
                    }
                }
            } catch (error: Throwable) {
                if (isRunning && token == videoAnalysisToken) {
                    runOnUiThread {
                        if (isRunning && token == videoAnalysisToken) {
                            stopTraining()
                            statusText.text = getString(R.string.status_video_error) +
                                ": " + (error.message ?: "")
                        }
                    }
                }
            } finally {
                retriever.release()
            }
        }
    }

    private fun extractVideoFrame(
        retriever: MediaMetadataRetriever,
        positionMs: Long,
        rotation: Int,
    ): Bitmap? {
        val timeUs = positionMs * 1000
        val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            retriever.getScaledFrameAtTime(
                timeUs,
                MediaMetadataRetriever.OPTION_CLOSEST,
                640,
                480,
            )
        } else {
            retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
        } ?: return null
        if (rotation == 0) return raw
        return try {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
            if (rotated !== raw) raw.recycle()
            rotated
        } catch (error: Throwable) {
            raw.recycle()
            null
        }
    }

    private fun showVideoFrame(frame: Bitmap) {
        val previous = lastDisplayedVideoFrame
        lastDisplayedVideoFrame = frame
        videoFrameView.setImageBitmap(frame)
        if (previous != null && previous !== frame && !previous.isRecycled) {
            previous.recycle()
        }
    }

    private fun clearVideoFrame() {
        videoFrameView.setImageDrawable(null)
        videoFrameView.visibility = View.GONE
        val previous = lastDisplayedVideoFrame
        lastDisplayedVideoFrame = null
        if (previous != null && !previous.isRecycled) previous.recycle()
    }

    private fun bindCamera(provider: ProcessCameraProvider) {
        val preview = preview ?: return
        val analysis = imageAnalysis ?: return

        hasFrontCamera = runCatching {
            provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
        }.getOrDefault(false)
        hasBackCamera = runCatching {
            provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
        }.getOrDefault(false)
        if (useFrontCamera && !hasFrontCamera) useFrontCamera = false
        if (!useFrontCamera && !hasBackCamera && hasFrontCamera) useFrontCamera = true

        val selector = if (useFrontCamera) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        provider.unbindAll()
        camera = null
        isTorchOn = false
        camera = provider.bindToLifecycle(this, selector, preview, analysis)
        overlayView.setMirrorHorizontally(useFrontCamera)
        updateCameraControls()
        statusText.text = getString(R.string.status_running)
    }

    private fun toggleFlash() {
        val camera = camera ?: return
        if (!camera.cameraInfo.hasFlashUnit()) {
            isTorchOn = false
            updateCameraControls()
            return
        }
        val nextState = !isTorchOn
        isTorchOn = nextState
        updateCameraControls()
        val future = camera.cameraControl.enableTorch(nextState)
        future.addListener({
            runCatching { future.get() }.onFailure {
                isTorchOn = false
                updateCameraControls()
                Toast.makeText(
                    this,
                    R.string.camera_flash_error,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun flipCamera() {
        if (!isRunning) return
        val provider = cameraProvider ?: return
        val targetFront = !useFrontCamera
        if (targetFront && !hasFrontCamera || !targetFront && !hasBackCamera) {
            updateCameraControls()
            return
        }

        val previousFront = useFrontCamera
        useFrontCamera = targetFront
        try {
            bindCamera(provider)
        } catch (error: Throwable) {
            useFrontCamera = previousFront
            runCatching { bindCamera(provider) }
            Toast.makeText(
                this,
                R.string.camera_flip_error,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun updateCameraControls() {
        videoButton.isEnabled = !isRunning
        val camera = camera
        val cameraActive = isRunning && camera != null
        val hasFlash = camera?.cameraInfo?.hasFlashUnit() == true
        val flashAvailable = cameraActive && hasFlash
        flashButton.isEnabled = flashAvailable
        flashButton.alpha = if (flashAvailable) 1f else DISABLED_CONTROL_ALPHA
        flashButton.setImageResource(
            if (isTorchOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off,
        )
        flashButton.contentDescription = getString(
            if (isTorchOn) R.string.camera_flash_off else R.string.camera_flash_on,
        )
        flashButton.tooltipText = if (cameraActive && !hasFlash) {
            getString(R.string.camera_flash_unavailable)
        } else {
            flashButton.contentDescription
        }

        val canFlip = cameraActive &&
            (if (useFrontCamera) hasBackCamera else hasFrontCamera)
        flipButton.isEnabled = canFlip
        flipButton.alpha = if (canFlip) 1f else DISABLED_CONTROL_ALPHA
        flipButton.contentDescription = getString(R.string.camera_flip)
        flipButton.tooltipText = if (cameraActive && !canFlip) {
            getString(R.string.camera_flip_unavailable)
        } else {
            flipButton.contentDescription
        }
    }

    private fun onPoseResult(
        landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>,
        imageWidth: Int,
        imageHeight: Int,
        elapsedOverride: Float? = null,
    ) {
        if (!isRunning) return
        val elapsed = elapsedOverride
            ?: ((SystemClock.elapsedRealtime() - sessionStartMs) / 1000f)
        val result: FrameResult
        val summary: SessionSummary
        try {
            result = engine.process(landmarks, elapsed)
            summary = engine.summary()
        } catch (error: Throwable) {
            runOnUiThread {
                if (isRunning) {
                    statusText.text = "C++ 处理失败：" + (error.message ?: "未知错误")
                }
            }
            return
        }

        runOnUiThread {
            if (!isRunning) return@runOnUiThread
            overlayView.update(landmarks)
            when {
                !result.poseDetected ->
                    statusText.text = "无法判断：请让肩、髋、膝、踝同时入镜"
                currentExercise.type == ExerciseType.HOLD ->
                    statusText.text = "保持中 · 质量 ${(result.quality * 100).roundToInt()}"
                result.repetitionCompleted ->
                    statusText.text = "完成 1 次！"
                else ->
                    statusText.text = if (result.inTarget) "到位" else "未到位"
            }
            cameraHintText.text = result.feedback.firstOrNull() ?: currentExercise.cameraHint
            updateMetrics(summary, elapsed)
        }
    }

    private fun stopTraining() {
        if (!isRunning) return
        val source = trainingSource
        val elapsedOverride = if (source == TrainingSource.VIDEO && videoProgressMs > 0L) {
            videoProgressMs / 1000f
        } else {
            null
        }
        isRunning = false
        videoAnalysisToken += 1
        exerciseSpinner.isEnabled = true
        startButton.isEnabled = true
        stopButton.isEnabled = false
        imageAnalysis?.clearAnalyzer()
        imageAnalysis = null
        preview = null
        camera = null
        isTorchOn = false
        cameraProvider?.unbindAll()
        closePoseLandmarkerAsync()
        overlayView.clear()
        clearVideoFrame()
        videoProgressText.visibility = View.GONE
        previewView.visibility = View.VISIBLE
        updateCameraControls()

        val elapsed = elapsedOverride
            ?: ((SystemClock.elapsedRealtime() - sessionStartMs) / 1000f)
        val summary = runCatching { engine.summary() }.getOrDefault(SessionSummary())
        updateMetrics(summary, elapsed)
        val quality = if (summary.averageQuality > 0f) {
            (summary.averageQuality * 100).roundToInt().toString()
        } else {
            "--"
        }
        statusText.text = if (currentExercise.type == ExerciseType.HOLD) {
            "已结束：保持 ${formatDuration(elapsed)}，质量 $quality，积分 ${summary.points}"
        } else {
            "已结束：${summary.repetitions} 次，质量 $quality，积分 ${summary.points}"
        }
        cameraHintText.text = currentExercise.cameraHint
        videoProgressMs = 0L
        trainingSource = TrainingSource.CAMERA

        // Store the finished session in this device's room log. Ranking is
        // recomputed from events, so the record must be the raw result rather
        // than a precomputed score.
        val exerciseCode = currentExercise.name.lowercase()
        LanRoom.record(
            context = this,
            exercise = exerciseCode,
            repetitions = summary.repetitions,
            durationSeconds = elapsed.roundToInt(),
            quality = summary.averageQuality.toDouble(),
            points = summary.points,
        )
    }

    /**
     * MediaPipe's TaskRunner.close() is not synchronized with process(), and the
     * camera analyzer also runs on [cameraExecutor]. Closing the landmarker from
     * the UI thread while a frame is still being inferred can tear down the
     * native graph underneath that frame and crash the process (SIGSEGV).
     *
     * Post the close to the analyzer's single-thread executor instead: it runs
     * after the in-flight analyze() call has returned, and no new frames can be
     * queued because the analyzer was cleared first.
     */
    private fun closePoseLandmarkerAsync() {
        val landmarker = poseLandmarker ?: return
        poseLandmarker = null
        if (cameraExecutor.isShutdown) {
            runCatching { landmarker.close() }
        } else {
            cameraExecutor.execute {
                runCatching { landmarker.close() }
            }
        }
    }

    private fun resetMetrics() {
        metricReps.text = if (currentExercise.type == ExerciseType.HOLD) "0:00" else "0"
        metricQuality.text = "--"
        metricPoints.text = "0"
        metricCalories.text = "0.0"
        metricActive.text = "0:00"
        metricNatural.text = "0:00"
    }

    private fun updateMetrics(summary: SessionSummary, elapsedSeconds: Float) {
        metricReps.text = if (currentExercise.type == ExerciseType.HOLD) {
            formatDuration(elapsedSeconds)
        } else {
            summary.repetitions.toString()
        }
        metricQuality.text = if (summary.averageQuality > 0f) {
            (summary.averageQuality * 100).roundToInt().toString()
        } else {
            "--"
        }
        metricPoints.text = summary.points.toString()
        metricCalories.text = String.format(Locale.US, "%.1f", summary.calories)
        metricActive.text = formatDuration(elapsedSeconds)
        metricNatural.text = formatDuration(elapsedSeconds)
    }

    private fun formatDuration(seconds: Float): String {
        val total = seconds.toInt().coerceAtLeast(0)
        return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
    }

    override fun onStop() {
        if (isRunning) stopTraining()
        super.onStop()
    }

    override fun onDestroy() {
        if (isRunning) stopTraining()
        // Let the analyzer finish the current frame and the queued close run
        // before tearing down the native engine/executor.
        closePoseLandmarkerAsync()
        cameraExecutor.shutdown()
        runCatching { cameraExecutor.awaitTermination(1, TimeUnit.SECONDS) }
        engine.close()
        super.onDestroy()
    }

    private enum class TrainingSource {
        CAMERA,
        VIDEO,
    }

    private companion object {
        const val DISABLED_CONTROL_ALPHA = 0.65f
        const val VIDEO_FRAME_INTERVAL_MS = 100L
        const val MAX_VIDEO_ANALYSIS_MS = 1_200_000L
    }
}
