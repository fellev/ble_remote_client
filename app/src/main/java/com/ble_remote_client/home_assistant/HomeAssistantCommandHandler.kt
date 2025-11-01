package com.example.yourapp  // Change this to your actual package

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.util.Log
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.ble_remote_client.AppConfig
import com.ble_remote_client.R
import com.ble_remote_client.client.BLEClient
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

        sendHomeAssistantCommand(entityId, entityFriendlyName, action, config.haUrl, config.haToken)
    }

    private fun sendHomeAssistantCommand(entityId: String, entityFriendlyName: String, action: String, haUrl: String, token: String) {
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
                    playNotificationSoundOnSpeaker(context, R.raw.programming_complete, 30)
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
        val stream = AudioManager.STREAM_MUSIC
        val isMusicPlaying = audioManager.isMusicActive

        // Prepare audio focus request (ducking) for when music is playing
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

        // If music is playing: play on current output (do not change routing/volume)
        if (isMusicPlaying) {
            Log.i(TAG, "Music active: playing notification on current output (will request duck).")

            // Request audio focus (duck)
            val gotFocus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val am = audioManager
                am.requestAudioFocus(afRequest!!
                ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    null, // legacy needs listener but we skip, most devices still allow ducking via flags
                    stream,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
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

            // Do NOT call setPreferredDevice or toggle speakerphone; play on current route
            mp.setOnCompletionListener { player ->
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        audioManager.abandonAudioFocusRequest(afRequest!!)
                    } else {
                        @Suppress("DEPRECATION")
                        audioManager.abandonAudioFocus(null)
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "Failed to abandon audio focus: ${e.message}")
                }
                player.release()
                Log.i(TAG, "Notification playback complete (music was active).")
            }

            mp.setOnErrorListener { player, what, extra ->
                Log.e(TAG, "Playback error (music active): what=$what extra=$extra")
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        audioManager.abandonAudioFocusRequest(afRequest!!)
                    } else {
                        @Suppress("DEPRECATION")
                        audioManager.abandonAudioFocus(null)
                    }
                } catch (_: Throwable) {}
                player.release()
                true
            }

            mp.start()
            return
        }

        // === else: no music playing -> use preferred-device + dual-route volume trick ===
        Log.i(TAG, "No music active: using speaker-preferred path with dual-route volume update.")

        // Save state
        val originalVolume = audioManager.getStreamVolume(stream)
        val maxVolume = audioManager.getStreamMaxVolume(stream)
        val tempVolume = (maxVolume * 0.3f).toInt().coerceAtLeast(1)

        val originalSpeakerphoneOn = audioManager.isSpeakerphoneOn
        val originalBluetoothA2dpOn = audioManager.isBluetoothA2dpOn

        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val speakerDevice = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }

        val handler = Handler(Looper.getMainLooper())

        var mp: MediaPlayer? = null
        var preferredSet = false
        var changedVolume = false

        fun safeSetStreamVolume(vol: Int) {
            try {
                audioManager.setStreamVolume(stream, vol, 0)
                Log.i(TAG, "setStreamVolume -> $vol")
            } catch (e: Throwable) {
                Log.w(TAG, "setStreamVolume failed: ${e.message}")
            }
        }

        fun setVolumeOnBothRoutes(targetVol: Int, onDone: () -> Unit) {
            try { audioManager.isSpeakerphoneOn = false } catch (e: Throwable) { Log.w(TAG, "speakerphoneOff failed: ${e.message}") }
            handler.postDelayed({
                safeSetStreamVolume(targetVol) // attempt update BT device volume
                handler.postDelayed({
                    try { audioManager.mode = AudioManager.MODE_NORMAL; audioManager.isSpeakerphoneOn = true }
                    catch (e: Throwable) { Log.w(TAG, "speakerphoneOn failed: ${e.message}") }
                    handler.postDelayed({
                        safeSetStreamVolume(targetVol) // update phone speaker volume
                        onDone()
                    }, 120)
                }, 120)
            }, 120)
        }

        fun restoreVolumeOnBothRoutes(originalVol: Int, onDone: () -> Unit) {
            try { audioManager.isSpeakerphoneOn = false } catch (e: Throwable) { Log.w(TAG, "speakerphoneOff restore failed: ${e.message}") }
            handler.postDelayed({
                safeSetStreamVolume(originalVol)
                handler.postDelayed({
                    try { audioManager.mode = AudioManager.MODE_NORMAL; audioManager.isSpeakerphoneOn = originalSpeakerphoneOn }
                    catch (e: Throwable) { Log.w(TAG, "restore speakerphone failed: ${e.message}") }
                    handler.postDelayed({
                        safeSetStreamVolume(originalVol)
                        onDone()
                    }, 120)
                }, 120)
            }, 120)
        }

        try {
            mp = MediaPlayer.create(context, resId)
            if (mp == null) {
                Log.e(TAG, "MediaPlayer.create returned null")
                return
            }

            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )

            try {
                if (speakerDevice != null) {
                    preferredSet = mp.setPreferredDevice(speakerDevice)
                    Log.i(TAG, "setPreferredDevice(speaker) -> $preferredSet")
                } else {
                    Log.w(TAG, "No built-in speaker AudioDeviceInfo found")
                }
            } catch (e: Throwable) {
                Log.w(TAG, "setPreferredDevice threw: ${e.message}")
                preferredSet = false
            }

            // If BT was on and no music is playing, set volume on both
            if (!audioManager.isMusicActive && originalBluetoothA2dpOn) {
                setVolumeOnBothRoutes(tempVolume) {
                    changedVolume = true
                    try {
                        Log.i(TAG, "Starting playback with preferredSet=$preferredSet")
                        mp.start()
                    } catch (e: Throwable) {
                        Log.e(TAG, "Failed to start playback: ${e.message}")
                    }
                }
            } else {
                // no music and no BT -> just change volume for speaker
                safeSetStreamVolume(tempVolume)
                changedVolume = true
                try {
                    audioManager.mode = AudioManager.MODE_NORMAL
                    audioManager.isSpeakerphoneOn = true
                } catch (e: Throwable) {}
                Log.i(TAG, "Starting playback (no music, BTconnected=$originalBluetoothA2dpOn)")
                mp.start()
            }

            mp.setOnCompletionListener { player ->
                handler.post {
                    if (changedVolume) {
                        restoreVolumeOnBothRoutes(originalVolume) {
                            try {
                                if (preferredSet) player.setPreferredDevice(null)
                            } catch (e: Throwable) { Log.w(TAG, "clear pref device failed: ${e.message}") }
                            try { player.release() } catch (_: Throwable) {}
                            Log.i(TAG, "Playback complete; volumes and routing restored")
                        }
                    } else {
                        try {
                            if (preferredSet) player.setPreferredDevice(null)
                            else audioManager.isSpeakerphoneOn = originalSpeakerphoneOn
                        } catch (e: Throwable) { Log.w(TAG, "cleanup routing error: ${e.message}") }
                        try { player.release() } catch (_: Throwable) {}
                    }
                }
            }

            mp.setOnErrorListener { player, what, extra ->
                Log.e(TAG, "Playback error what=$what extra=$extra")
                handler.post {
                    try {
                        if (changedVolume) {
                            restoreVolumeOnBothRoutes(originalVolume) {
                                try { if (preferredSet) player.setPreferredDevice(null) } catch (_: Throwable) {}
                                try { player.release() } catch (_: Throwable) {}
                            }
                        } else {
                            try { if (preferredSet) player.setPreferredDevice(null) else audioManager.isSpeakerphoneOn = originalSpeakerphoneOn } catch (_: Throwable) {}
                            try { player.release() } catch (_: Throwable) {}
                        }
                    } catch (e: Throwable) {
                        Log.w(TAG, "Error during error-path cleanup: ${e.message}")
                    }
                }
                true
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Unexpected error preparing playback: ${e.message}")
            try { mp?.release() } catch (_: Throwable) {}
            if (mp == null) return
            if (audioManager.isMusicActive) {
                // if music started meanwhile, nothing to restore
                return
            }
            // best-effort restore if changedVolume
            try { if (audioManager.isBluetoothA2dpOn) restoreVolumeOnBothRoutes(originalVolume) {} else if (audioManager.isSpeakerphoneOn != audioManager.isSpeakerphoneOn) audioManager.isSpeakerphoneOn = originalSpeakerphoneOn } catch (_: Throwable) {}
        }
    }
}
