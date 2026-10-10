package com.example.ets2headtracker

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.util.concurrent.Executors
import kotlin.math.abs

data class Live(val yaw: Float = 0f, val pitch: Float = 0f, val roll: Float = 0f)

class HeadTracker(
    private val context: Context,
    private val settings: Settings,
    private val link: UdpLink,
) {
    var tracking by mutableStateOf(false)
        private set
    var faceFound by mutableStateOf(false)
        private set
    var live by mutableStateOf(Live())
        private set
    var fps by mutableIntStateOf(0)
        private set
    var delegateName by mutableStateOf("-")
        private set

    private val executor = Executors.newSingleThreadExecutor()
    private var landmarker: FaceLandmarker? = null
    private var useCase: ImageAnalysis? = null
    private val filters = Array(6) { OneEuro(settings.minCutoff, settings.beta) }

    @Volatile private var recenterFlag = true
    private var ref: PoseMath.Frame? = null
    private var lastNs = 0L
    private var frames = 0
    private var fpsStart = 0L

    init {
        executor.execute { createLandmarker() }
    }

    fun enableTracking(on: Boolean) {
        tracking = on
        if (on) {
            recenterFlag = true
            lastNs = 0L
        } else {
            faceFound = false
            fps = 0
        }
        applyAnalyzer()
    }

    fun recenter() {
        recenterFlag = true
    }

    fun startCamera(owner: LifecycleOwner, previewView: PreviewView) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(640, 480),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                            ),
                        )
                        .build(),
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            useCase = analysis
            provider.unbindAll()
            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
            applyAnalyzer()
        }, ContextCompat.getMainExecutor(context))
    }

    fun close() {
        useCase?.clearAnalyzer()
        executor.execute {
            landmarker?.close()
            landmarker = null
        }
        executor.shutdown()
    }

    private fun applyAnalyzer() {
        val analysis = useCase ?: return
        if (tracking) analysis.setAnalyzer(executor) { analyze(it) } else analysis.clearAnalyzer()
    }

    private fun build(delegate: Delegate): FaceLandmarker {
        val base = BaseOptions.builder()
            .setModelAssetPath("face_landmarker.task")
            .setDelegate(delegate)
            .build()
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumFaces(1)
            .setOutputFacialTransformationMatrixes(true)
            .setResultListener { result, _ -> onResult(result) }
            .setErrorListener { e -> Log.e(TAG, "MediaPipe: ${e.message}") }
            .build()
        return FaceLandmarker.createFromOptions(context, options)
    }

    private fun createLandmarker() {
        landmarker = try {
            build(Delegate.GPU).also { delegateName = "GPU" }
        } catch (e: Exception) {
            Log.w(TAG, "GPU gagal (${e.message}), pakai CPU")
            try {
                build(Delegate.CPU).also { delegateName = "CPU" }
            } catch (e2: Exception) {
                Log.e(TAG, "FaceLandmarker gagal dibuat: ${e2.message}")
                delegateName = "error"
                null
            }
        }
    }

    private fun analyze(image: ImageProxy) {
        val lm = landmarker
        try {
            if (lm != null) {
                val mpImage = BitmapImageBuilder(image.toBitmap()).build()
                val opts = ImageProcessingOptions.builder()
                    .setRotationDegrees(image.imageInfo.rotationDegrees)
                    .build()
                lm.detectAsync(mpImage, opts, SystemClock.uptimeMillis())
            }
        } catch (e: Exception) {
            Log.e(TAG, "analyze: ${e.message}")
        } finally {
            image.close()
        }
    }

    private fun onResult(result: FaceLandmarkerResult) {
        if (!tracking) return
        val matrices = result.facialTransformationMatrixes()
        val raw = if (matrices.isPresent && matrices.get().isNotEmpty()) matrices.get()[0] else null
        val frame = raw?.let { PoseMath.parse(it) }
        if (frame == null) {
            faceFound = false
            return
        }
        faceFound = true

        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 1f / 30f else ((now - lastNs) / 1e9f).coerceIn(0.005f, 0.2f)
        lastNs = now

        if (recenterFlag || ref == null) {
            ref = frame
            recenterFlag = false
            filters.forEach { it.reset() }
        }
        val rel = PoseMath.relative(ref!!, frame)

        filters.forEach {
            it.minCutoff = settings.minCutoff
            it.beta = settings.beta
        }
        val out = FloatArray(6)
        val shown = FloatArray(3)
        for (i in 0..2) {
            val v = filters[i].filter(rel[i], dt)
            val axis = settings.axes[i]
            val mapped = Curve.map(axis.points.toList(), v)
            out[i] = if (axis.invert) -mapped else mapped
            shown[i] = abs(v)
        }
        if (settings.sendPosition) {
            for (i in 3..5) out[i] = filters[i].filter(rel[i], dt)
        }
        link.sendPose(out)
        live = Live(shown[0], shown[1], shown[2])

        frames++
        if (fpsStart == 0L) fpsStart = now
        if (now - fpsStart >= 1_000_000_000L) {
            fps = frames
            frames = 0
            fpsStart = now
        }
    }

    private companion object {
        const val TAG = "HeadTracker"
    }
}
