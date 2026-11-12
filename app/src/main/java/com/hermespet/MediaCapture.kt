package com.hermespet

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.*

/**
 * Foto / video / rekam suara TANPA membuka app kamera (Camera2 + MediaRecorder langsung).
 * Hasil disimpan ke galeri (Pictures|Movies|Music/Mocipet). Butuh izin CAMERA / RECORD_AUDIO
 * dan overlay Mocipet yang sedang aktif (syarat Android untuk kamera di latar belakang).
 * shortcut: satu rekaman sekaligus, tanpa pratinjau; foto belakang memakai warm-up 700ms untuk AE.
 */
object MediaCapture {
    enum class Mode { IDLE, VIDEO, AUDIO }
    @Volatile var mode = Mode.IDLE; private set

    private var thread: HandlerThread? = null
    private var h: Handler? = null
    private var cam: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var rec: MediaRecorder? = null
    private var pfd: android.os.ParcelFileDescriptor? = null
    private var pendingUri: android.net.Uri? = null
    private var autoStop: Runnable? = null

    private fun has(c: Context, p: String) = c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun handler(): Handler {
        if (thread == null) thread = HandlerThread("mocicam").also { it.start() }
        return h ?: Handler(thread!!.looper).also { h = it }
    }

    private fun camId(c: Context, front: Boolean): String? {
        val cm = c.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val want = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        return cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == want
        }
    }

    private fun insert(c: Context, coll: android.net.Uri, name: String, mime: String, rel: String) =
        c.contentResolver.insert(coll, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        })

    private fun publish(c: Context, uri: android.net.Uri?) {
        uri ?: return
        c.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    }

    private fun closeCam() {
        runCatching { session?.close() }; runCatching { cam?.close() }
        session = null; cam = null
    }

    // ------------------------------------------------------------------ foto
    fun photo(c: Context, front: Boolean, done: (String) -> Unit) {
        if (!has(c, Manifest.permission.CAMERA)) return done("Izinkan Kamera dulu di Pengaturan Mocipet 📷")
        if (mode != Mode.IDLE) return done("Lagi merekam, stop dulu ya 🔴")
        val id = camId(c, front) ?: return done("Kamera ${if (front) "depan" else "belakang"} tidak ada 😞")
        val cm = c.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ch = cm.getCameraCharacteristics(id)
        val size = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            .getOutputSizes(ImageFormat.JPEG).maxByOrNull { it.width.toLong() * it.height }!!
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 1)
        val warm = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2) // sasaran "preview" untuk AE
        val hd = handler()
        var finished = false
        fun end(msg: String) { if (finished) return; finished = true; runCatching { reader.close(); warm.close() }; closeCam(); done(msg) }
        warm.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, hd)
        reader.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            val bytes = img.planes[0].buffer.let { b -> ByteArray(b.remaining()).also { b.get(it) } }
            img.close()
            try {
                val uri = insert(c, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "Moci_${stamp()}.jpg",
                    "image/jpeg", "Pictures/Mocipet")!!
                c.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
                publish(c, uri)
                end("Foto tersimpan di galeri (Mocipet) 📸")
            } catch (e: Exception) { end("Gagal simpan foto: ${e.message}") }
        }, hd)
        try {
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    cam = d
                    d.createCaptureSession(listOf(reader.surface, warm.surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            session = s
                            val pv = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(warm.surface) }
                            s.setRepeatingRequest(pv.build(), null, hd)
                            hd.postDelayed({
                                runCatching {
                                    val st = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                        addTarget(reader.surface)
                                        set(CaptureRequest.JPEG_ORIENTATION, ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90)
                                    }
                                    s.stopRepeating(); s.capture(st.build(), null, hd)
                                }.onFailure { end("Gagal ambil foto: ${it.message}") }
                            }, 700)
                        }
                        override fun onConfigureFailed(s: CameraCaptureSession) = end("Kamera gagal disiapkan 😖")
                    }, hd)
                }
                override fun onDisconnected(d: CameraDevice) { cam = d; end("Kamera dipakai app lain 😞") }
                override fun onError(d: CameraDevice, e: Int) { cam = d; end("Kamera error ($e) 😖") }
            }, hd)
            hd.postDelayed({ end("Kamera tidak merespons (timeout) 😖") }, 8000)
        } catch (e: SecurityException) { end("Izin kamera ditolak 📷") }
        catch (e: Exception) { end("Gagal membuka kamera: ${e.message}") }
    }

    // ----------------------------------------------------------------- video
    fun video(c: Context, front: Boolean, seconds: Int, done: (String) -> Unit) {
        if (!has(c, Manifest.permission.CAMERA)) return done("Izinkan Kamera dulu di Pengaturan Mocipet 🎥")
        if (mode != Mode.IDLE) return done("Masih merekam. Bilang \"stop rekam\" 🔴")
        val id = camId(c, front) ?: return done("Kamera ${if (front) "depan" else "belakang"} tidak ada 😞")
        val cm = c.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ori = cm.getCameraCharacteristics(id).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val hd = handler()
        try {
            val uri = insert(c, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "Moci_${stamp()}.mp4",
                "video/mp4", "Movies/Mocipet")!!
            pendingUri = uri; pfd = c.contentResolver.openFileDescriptor(uri, "w")
            val r = MediaRecorder().apply {
                val mic = has(c, Manifest.permission.RECORD_AUDIO)
                if (mic) setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(pfd!!.fileDescriptor)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                if (mic) { setAudioEncoder(MediaRecorder.AudioEncoder.AAC); setAudioEncodingBitRate(128_000) }
                setVideoSize(1280, 720); setVideoFrameRate(30); setVideoEncodingBitRate(6_000_000)
                setOrientationHint(if (front) (360 - ori) % 360 else ori)
                prepare()
            }
            rec = r; mode = Mode.VIDEO
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    cam = d
                    d.createCaptureSession(listOf(r.surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            session = s
                            s.setRepeatingRequest(d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                                .apply { addTarget(r.surface) }.build(), null, hd)
                            r.start()
                            done(if (seconds > 0) "🔴 Merekam video $seconds detik…" else "🔴 Merekam video… bilang \"stop rekam\"")
                            if (seconds > 0) schedule(c, hd, seconds) { msg -> done(msg) }
                        }
                        override fun onConfigureFailed(s: CameraCaptureSession) = abort(c, "Kamera gagal disiapkan 😖", done)
                    }, hd)
                }
                override fun onDisconnected(d: CameraDevice) { cam = d; abort(c, "Kamera dipakai app lain 😞", done) }
                override fun onError(d: CameraDevice, e: Int) { cam = d; abort(c, "Kamera error ($e) 😖", done) }
            }, hd)
        } catch (e: SecurityException) { abort(c, "Izin kamera ditolak 🎥", done) }
        catch (e: Exception) { abort(c, "Gagal mulai video: ${e.message}", done) }
    }

    // ------------------------------------------------------------------ suara
    fun audio(c: Context, seconds: Int, done: (String) -> Unit) {
        if (!has(c, Manifest.permission.RECORD_AUDIO)) return done("Izinkan Mikrofon dulu di Pengaturan Mocipet 🎙️")
        if (mode != Mode.IDLE) return done("Masih merekam. Bilang \"stop rekam\" 🔴")
        try {
            val uri = insert(c, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "Moci_${stamp()}.m4a",
                "audio/mp4", "Music/Mocipet")!!
            pendingUri = uri; pfd = c.contentResolver.openFileDescriptor(uri, "w")
            rec = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128_000); setAudioSamplingRate(44_100)
                setOutputFile(pfd!!.fileDescriptor)
                prepare(); start()
            }
            mode = Mode.AUDIO
            done(if (seconds > 0) "🔴 Merekam suara $seconds detik…" else "🔴 Merekam suara… bilang \"stop rekam\"")
            if (seconds > 0) schedule(c, handler(), seconds) { msg -> done(msg) }
        } catch (e: Exception) { abort(c, "Gagal mulai rekam suara: ${e.message}", done) }
    }

    // ------------------------------------------------------------------ stop
    private fun schedule(c: Context, hd: Handler, sec: Int, done: (String) -> Unit) {
        autoStop = Runnable { done(stop(c)) }
        hd.postDelayed(autoStop!!, sec * 1000L)
    }

    fun stop(c: Context): String {
        if (mode == Mode.IDLE) return "Nggak lagi merekam 🙂"
        val was = mode
        autoStop?.let { handler().removeCallbacks(it) }; autoStop = null
        val ok = runCatching { rec?.stop() }.isSuccess // gagal bila terlalu pendek
        runCatching { rec?.release() }; rec = null
        closeCam(); runCatching { pfd?.close() }; pfd = null
        mode = Mode.IDLE
        val u = pendingUri; pendingUri = null
        return if (ok) { publish(c, u); if (was == Mode.VIDEO) "Video tersimpan di galeri (Mocipet) 🎬" else "Rekaman tersimpan (Music/Mocipet) 🎙️" }
        else { u?.let { runCatching { c.contentResolver.delete(it, null, null) } }; "Rekaman terlalu pendek, dibuang 😅" }
    }

    private fun abort(c: Context, msg: String, done: (String) -> Unit) {
        runCatching { rec?.release() }; rec = null; closeCam()
        runCatching { pfd?.close() }; pfd = null
        pendingUri?.let { runCatching { c.contentResolver.delete(it, null, null) } }; pendingUri = null
        mode = Mode.IDLE
        done(msg)
    }
}
