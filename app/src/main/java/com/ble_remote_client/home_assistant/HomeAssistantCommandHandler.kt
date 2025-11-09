package com.example.yourapp  // Change this to your actual package

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.ble_remote_client.AppConfig
import com.ble_remote_client.R
import com.google.gson.Gson
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

class HomeAssistantCommandHandler(private val context: Context) {

    private val client = OkHttpClient()

    fun handleBleCommand(command: String) {
        val prefs = context.getSharedPreferences("button_config_prefs", Context.MODE_PRIVATE)
        val json = prefs.getString("appConfig", null) ?: return
        val config = Gson().fromJson(json, AppConfig::class.java)

        val parts = command.split(":")
        if (parts.size != 2) {
            Log.w(TAG, "Invalid command format: $command")
            return
        }

        val type = parts[0]
        val index = parts[1].toIntOrNull()?.minus(1) ?: return

        if (index !in config.buttonConfigs.indices) {
            Log.w(TAG, "Button index out of range: $index")
            return
        }

        val buttonConfig = config.buttonConfigs[index]
        val entityId = if (type == "short") buttonConfig.shortPressEntity else buttonConfig.longPressEntity
        val action = if (type == "short") buttonConfig.shortPressAction else buttonConfig.longPressAction
        val entityFriendlyName = config.buttonConfigs[index].name

        if (entityId.isBlank()) {
            Log.w(TAG, "No entity configured for $type press on button ${index + 1}")
            return
        }

        sendHomeAssistantCommand(entityId, entityFriendlyName, action, config.haUrl, config.haToken, config.notificationVolume)
    }

    private fun sendHomeAssistantCommand(entityId: String, entityFriendlyName: String, action: String, haUrl: String, token: String, volumePercent: Int) {
        val serviceDomain = entityId.substringBefore(".")
        val actionValue = when (action.lowercase()) {
            "on" -> "turn_on"
            "off" -> "turn_off"
            "toggle" -> "toggle"
            else -> throw IllegalArgumentException("Unsupported action: $action")
        }

        val url = "$haUrl/api/services/$serviceDomain/$actionValue"

        val body = JSONObject().apply {
            put("entity_id", entityId)
        }

        //Add a log statement to print the request body
        Log.d(TAG, "Request body: $body")

        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaTypeOrNull()))
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Content-Type", "application/json")
            .build()

        //Add a log statement to print the request URL
        Log.d(TAG, "Request URL: ${request.url}")

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Failed to call Home Assistant service: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                Log.i(TAG, "Home Assistant response: ${response.code}")
                if (response.isSuccessful) {
                    Log.i(TAG, "Successfully sent command to Home Assistant for $entityFriendlyName")
                    playNotificationSoundOnSpeaker(context, R.raw.programming_complete, volumePercent)
                    vibrate()
                } else {
                }
            }
        })
    }

    companion object {
        private const val TAG = "HACommandHandler"
    }

    private fun vibrate() {
        val duration = 500L // Vibrate for 100 milliseconds
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            val vibrator = vibratorManager.defaultVibrator
            vibrator.vibrate(VibrationEffect.createOneShot(duration, 255))
        } else {
            @Suppress("DEPRECATION")
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (vibrator.hasVibrator()) {
                vibrator.vibrate(duration)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun playNotificationSoundOnSpeaker(context: Context, resId: Int, volumePercent: Int) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val isMusicPlaying = audioManager.isMusicActive
        val handler = Handler(Looper.getMainLooper())
        val clampedPercent = volumePercent.coerceIn(0, 100)

        // Save originals
        val stream = AudioManager.STREAM_MUSIC
        val originalStreamVol = try { audioManager.getStreamVolume(stream) } catch (_: Throwable) { 0 }
        val maxStreamVol = try { audioManager.getStreamMaxVolume(stream) } catch (_: Throwable) { 15 }
        val targetVol = (maxStreamVol * (clampedPercent / 100f)).toInt().coerceAtLeast(1)

        val originalMode = try { audioManager.mode } catch (_: Throwable) { AudioManager.MODE_NORMAL }
        val originalSpeakerphone = try { audioManager.isSpeakerphoneOn } catch (_: Throwable) { false }

        // Find builtin speaker device if available
        val outputs = try { audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) } catch (_: Throwable) { emptyArray<AudioDeviceInfo>() }
        val builtinSpeaker = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }

        var player: MediaPlayer? = null
        var commDeviceUsed = false
        var preferredDeviceUsed = false
        var speakerphoneToggled = false
        var restoreScheduled = false

        var afRequest: AudioFocusRequest? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            afRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                ).build()
        }

        if (isMusicPlaying) {
            Log.i(TAG, "Music active: playing notification on current output (duck).")
            val gotFocus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioManager.requestAudioFocus(afRequest!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(null, stream, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK) ==
                        AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
            Log.i(TAG, "Audio focus (duck) granted=$gotFocus")

            val mp = MediaPlayer.create(context, resId) ?: run {
                Log.e(TAG, "MediaPlayer.create returned null")
                return
            }
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.setOnCompletionListener { player ->
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) audioManager.abandonAudioFocusRequest(afRequest!!)
                    else @Suppress("DEPRECATION") audioManager.abandonAudioFocus(null)
                } catch (e: Throwable) { Log.w(TAG, "abandonAudioFocus failed: ${e.message}") }
                player.release()
            }
            mp.setOnErrorListener { player, what, extra ->
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) audioManager.abandonAudioFocusRequest(afRequest!!)
                    else @Suppress("DEPRECATION") audioManager.abandonAudioFocus(null)
                } catch (_: Throwable) {}
                player.release()
                true
            }
            mp.start()
            return
        }

        fun restoreAndFinish() {
            if (restoreScheduled) return
            restoreScheduled = true

            try {
                // restore stream volume
                audioManager.setStreamVolume(stream, originalStreamVol, 0)
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to restore stream volume: ${e.message}")
            }

            try {
                if (commDeviceUsed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    try { audioManager.clearCommunicationDevice() } catch (e: Throwable) { Log.w(TAG, "clearCommunicationDevice failed: ${e.message}") }
                }
            } catch (_: Throwable) {}

            try {
                if (speakerphoneToggled) audioManager.isSpeakerphoneOn = originalSpeakerphone
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to restore speakerphone: ${e.message}")
            }

            try { audioManager.mode = originalMode } catch (e: Throwable) { Log.w(TAG, "Failed to restore audio mode: ${e.message}") }

            try {
                player?.let {
                    try { it.setOnCompletionListener(null) } catch (_: Throwable) {}
                    try { it.setOnErrorListener(null) } catch (_: Throwable) {}
                    try { it.release() } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}

        }

        // Configure player creation function (so we can re-create on fallback if needed)
        fun createPlayer(): MediaPlayer? {
            val mp = try { MediaPlayer.create(context, resId) } catch (e: Throwable) {
                Log.e(TAG, "MediaPlayer.create threw: ${e.message}")
                null
            } ?: return null

            // Default attributes — will be adjusted per-path if needed
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )

            mp.setOnCompletionListener {
                restoreAndFinish()
            }
            mp.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "Playback error what=$what extra=$extra")
                restoreAndFinish()
                true
            }
            return mp
        }

        // Try path 1: communication-device (API >= 31). This is the cleanest for routing to speaker.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && builtinSpeaker != null) {
            try {
                player = createPlayer()
                if (player == null) {
                    Log.w(TAG, "player creation failed on comm-device path")
                } else {
                    // switch to communication mode
                    try { audioManager.mode = AudioManager.MODE_IN_COMMUNICATION } catch (e: Throwable) { Log.w(TAG, "set MODE_IN_COMMUNICATION failed: ${e.message}") }

                    // set communication device to builtin speaker
                    val setOk = try {
                        audioManager.setCommunicationDevice(builtinSpeaker)
                    } catch (e: Throwable) {
                        Log.w(TAG, "setCommunicationDevice threw: ${e.message}")
                        false
                    }
                    Log.i(TAG, "setCommunicationDevice -> $setOk")

                    // set temporary system media volume to target (so MediaPlayer won't be silent)
                    Log.i(TAG, "setStreamVolume to $targetVol")
                    try { audioManager.setStreamVolume(stream, targetVol, 0) } catch (e: Throwable) { Log.w(TAG, "setStreamVolume failed: ${e.message}") }

                    // when using comm-device, use voice communication usage for best routing
                    try {
                        player.setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                        )
                    } catch (_: Throwable) {}

                    commDeviceUsed = setOk
                    // start playback
                    try {
                        player.start()
                        return
                    } catch (e: Throwable) {
                        Log.w(TAG, "player.start failed on comm-device path: ${e.message}")
                        // fallthrough to fallback, but ensure we will release and recreate
                        try { player.release() } catch (_: Throwable) {}
                        player = null
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Comm-device path exception: ${e.message}")
                try { player?.release() } catch (_: Throwable) {}
                player = null
            }
        }

        // Path 2: try MediaPlayer.setPreferredDevice (per-player routing)
        try {
            player = createPlayer()
            if (player != null && builtinSpeaker != null) {
                val prefOk = try { player.setPreferredDevice(builtinSpeaker) } catch (e: Throwable) {
                    Log.w(TAG, "setPreferredDevice threw: ${e.message}")
                    false
                }
                Log.i(TAG, "setPreferredDevice -> $prefOk")
                if (prefOk) {
                    preferredDeviceUsed = true
                    // set temporary system media volume so stream isn't silent
                    try { audioManager.setStreamVolume(stream, targetVol, 0) } catch (e: Throwable) { Log.w(TAG, "setStreamVolume failed: ${e.message}") }
                    try {
                        player.start()
                        return
                    } catch (e: Throwable) {
                        Log.w(TAG, "player.start failed with preferred device: ${e.message}")
                        try { player.release() } catch (_: Throwable) {}
                        player = null
                    }
                } else {
                    // preferred device failed — we'll fall through to speakerphone toggle fallback
                    try { player.release() } catch (_: Throwable) {}
                    player = null
                }
            } else {
                try { player?.release() } catch (_: Throwable) {}
                player = null
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Preferred-device path exception: ${e.message}")
            try { player?.release() } catch (_: Throwable) {}
            player = null
        }

        // Path 3: fallback — toggle speakerphone temporarily and start
        try {
            player = createPlayer()
            if (player == null) {
                Log.e(TAG, "player creation failed for fallback path")
                restoreAndFinish()
                return
            }

            // Toggle speakerphone on so playback routes to phone speaker—best-effort.
            try {
                audioManager.mode = AudioManager.MODE_NORMAL
                audioManager.isSpeakerphoneOn = true
                speakerphoneToggled = true
            } catch (e: Throwable) {
                Log.w(TAG, "speakerphone toggle failed: ${e.message}")
            }

            // Wait briefly for routing to settle, then set stream volume and start
            handler.postDelayed({
                try { audioManager.setStreamVolume(stream, targetVol, 0) } catch (e: Throwable) { Log.w(TAG, "setStreamVolume failed: ${e.message}") }
                try {
                    player.start()
                } catch (e: Throwable) {
                    Log.e(TAG, "fallback player.start failed: ${e.message}")
                    restoreAndFinish()
                }
            }, 120L)

            return
        } catch (e: Throwable) {
            Log.w(TAG, "Fallback path exception: ${e.message}")
            try { player?.release() } catch (_: Throwable) {}
            restoreAndFinish()
        }
    }
}
