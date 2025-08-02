package com.example.yourapp  // Change this to your actual package

import android.content.Context
import android.util.Log
import com.ble_remote_client.AppConfig
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
            }
        })
    }

    companion object {
        private const val TAG = "HACommandHandler"
    }
}
