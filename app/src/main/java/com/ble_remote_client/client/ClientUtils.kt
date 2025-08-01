package com.ble_remote_client.client

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

object ClientUtils {

    fun startClientService(context: Context) {
        val intent = Intent(context, BLEClientService::class.java).apply {
            action = BLEClientService.ACTION_START_CLIENT
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun stopClientService(context: Context) {
        val intent = Intent(context, BLEClientService::class.java).apply {
            action = BLEClientService.ACTION_STOP_CLIENT
        }
        ContextCompat.startForegroundService(context, intent)
    }
}