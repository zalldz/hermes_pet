package com.hermespet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Cek mood lewat kamera depan: ambil satu frame, analisis wajah ON-DEVICE
 * dengan ML Kit (senyum & mata terbuka). Foto TIDAK keluar dari HP kecuali
 * pengguna mengaktifkan "kirim foto ke agent".
 */
object MoodCamera {
    private const val TAG = "MoodCamera"

    private class OneShotLifecycle : LifecycleOwner {
        private val reg = LifecycleRegistry(this)
        init { reg.currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = reg
        fun destroy() { reg.currentState = Lifecycle.State.DESTROYED }
    }

    fun capture(ctx: Context, includeImage: Boolean, onResult: (JSONObject) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        main.post {
            val owner = OneShotLifecycle()
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener({
                val provider = try { future.get() } catch (e: Exception) {
                    onResult(error("camera_provider: ${e.message}")); return@addListener
                }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                try {
                    provider.unbindAll()
                    provider.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, capture)
                } catch (e: Exception) {
                    onResult(error("bind: ${e.message}")); return@addListener
                }

                fun cleanup() = main.post {
                    runCatching { provider.unbindAll() }
                    owner.destroy()
                }

                capture.takePicture(ContextCompat.getMainExecutor(ctx),
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(proxy: ImageProxy) {
                            val buf = proxy.planes[0].buffer
                            val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                            val rotation = proxy.imageInfo.rotationDegrees
                            proxy.close()
                            cleanup()
                            Thread { analyze(bytes, rotation, includeImage, onResult) }.start()
                        }

                        override fun onError(e: ImageCaptureException) {
                            cleanup()
                            onResult(error("capture: ${e.message}"))
                        }
                    })
            }, ContextCompat.getMainExecutor(ctx))
        }
    }

    private fun analyze(
        jpeg: ByteArray, rotation: Int, includeImage: Boolean, onResult: (JSONObject) -> Unit
    ) {
        val raw = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            ?: return onResult(error("decode"))
        val bmp = if (rotation != 0) {
            val m = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        } else raw

        // perkecil supaya cepat dianalisis
        val scale = 480f / maxOf(bmp.width, bmp.height)
        val small = if (scale < 1f) {
            Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
        } else bmp

        val options = FaceDetectorOptions.Builder()
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .build()
        val detector = FaceDetection.getClient(options)

        detector.process(InputImage.fromBitmap(small, 0))
            .addOnSuccessListener { faces ->
                val result = JSONObject()
                if (faces.isEmpty()) {
                    result.put("face_detected", false)
                } else {
                    val f = faces[0]
                    val smile = f.smilingProbability ?: -1f
                    val eyes = listOfNotNull(f.leftEyeOpenProbability, f.rightEyeOpenProbability)
                        .let { if (it.isEmpty()) -1f else it.average().toFloat() }
                    result.put("face_detected", true)
                        .put("smile", smile)
                        .put("eyes_open", eyes)
                        .put("mood_guess", guess(smile, eyes))
                }
                if (includeImage) {
                    val out = ByteArrayOutputStream()
                    val thumbScale = 320f / maxOf(small.width, small.height)
                    val thumb = if (thumbScale < 1f) Bitmap.createScaledBitmap(
                        small, (small.width * thumbScale).toInt(), (small.height * thumbScale).toInt(), true
                    ) else small
                    thumb.compress(Bitmap.CompressFormat.JPEG, 70, out)
                    result.put("image_b64", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
                }
                detector.close()
                onResult(result)
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "mlkit: ${e.message}")
                detector.close()
                onResult(error("mlkit: ${e.message}"))
            }
    }

    private fun guess(smile: Float, eyes: Float): String = when {
        smile > 0.6f -> "happy"
        eyes in 0f..0.3f -> "sleepy"
        smile in 0f..0.1f -> "flat"
        else -> "neutral"
    }

    private fun error(msg: String?): JSONObject =
        JSONObject().put("face_detected", false).put("error", msg ?: "unknown")
}
