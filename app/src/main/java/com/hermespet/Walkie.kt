package com.hermespet

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.hermespet.Prefs.fbApiKey
import com.hermespet.Prefs.fbAppId
import com.hermespet.Prefs.fbDbUrl
import com.hermespet.Prefs.fbProjectId
import com.hermespet.Prefs.mociId

/**
 * Walkie-talkie (push-to-talk) antar pengguna Mocipet via Firebase Realtime
 * Database. Init RUNTIME dari config pengguna (tanpa google-services.json),
 * jadi build tetap zero-error walau Firebase belum diisi. Nonaktif total
 * sampai dikonfigurasi.
 */
object Walkie {
    private const val TAG = "Walkie"
    private var db: FirebaseDatabase? = null
    private var inboxRef: DatabaseReference? = null
    private var listener: ChildEventListener? = null

    fun isConfigured(ctx: Context): Boolean =
        ctx.fbApiKey.isNotBlank() && ctx.fbAppId.isNotBlank() &&
            ctx.fbProjectId.isNotBlank() && ctx.fbDbUrl.isNotBlank()

    private fun database(ctx: Context): FirebaseDatabase? {
        if (!isConfigured(ctx)) return null
        db?.let { return it }
        return try {
            val app = try {
                FirebaseApp.getInstance("moci")
            } catch (e: IllegalStateException) {
                val opts = FirebaseOptions.Builder()
                    .setApiKey(ctx.fbApiKey)
                    .setApplicationId(ctx.fbAppId)
                    .setProjectId(ctx.fbProjectId)
                    .setDatabaseUrl(ctx.fbDbUrl)
                    .build()
                FirebaseApp.initializeApp(ctx.applicationContext, opts, "moci")
            }
            FirebaseDatabase.getInstance(app).also { db = it }
        } catch (e: Throwable) {
            Log.w(TAG, "init Firebase gagal: ${e.message}"); null
        }
    }

    /** Dengarkan pesan suara masuk untuk ID-ku. onVoice(dariId, bytesAudio). */
    fun startListening(ctx: Context, onVoice: (String, ByteArray) -> Unit) {
        val database = database(ctx) ?: return
        stopListening()
        val ref = database.getReference("inbox").child(ctx.mociId)
        inboxRef = ref
        val l = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, prev: String?) {
                try {
                    val from = snapshot.child("from").getValue(String::class.java) ?: "?"
                    val b64 = snapshot.child("audio").getValue(String::class.java)
                    if (b64 != null) onVoice(from, Base64.decode(b64, Base64.NO_WRAP))
                } catch (e: Exception) {
                    Log.w(TAG, "terima gagal: ${e.message}")
                } finally {
                    runCatching { snapshot.ref.removeValue() } // sudah diputar → hapus
                }
            }
            override fun onChildChanged(s: DataSnapshot, p: String?) {}
            override fun onChildRemoved(s: DataSnapshot) {}
            override fun onChildMoved(s: DataSnapshot, p: String?) {}
            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "listener batal: ${error.message}")
            }
        }
        ref.addChildEventListener(l)
        listener = l
    }

    fun stopListening() {
        listener?.let { l -> inboxRef?.removeEventListener(l) }
        listener = null; inboxRef = null
    }

    /** Kirim pesan suara ke targetId. onResult(sukses, pesan). */
    fun send(ctx: Context, targetId: String, audio: ByteArray, onResult: (Boolean, String) -> Unit) {
        val database = database(ctx) ?: return onResult(false, "Walkie belum diatur 🥺")
        if (targetId.isBlank()) return onResult(false, "ID tujuan kosong")
        if (audio.isEmpty()) return onResult(false, "Rekamannya kosong")
        val msg = mapOf(
            "from" to ctx.mociId,
            "audio" to Base64.encodeToString(audio, Base64.NO_WRAP),
            "ts" to ServerValue.TIMESTAMP
        )
        database.getReference("inbox").child(targetId).push().setValue(msg)
            .addOnSuccessListener { onResult(true, "Terkirim ke $targetId 🎙️") }
            .addOnFailureListener { e -> onResult(false, "Gagal kirim: ${e.message}") }
    }
}
