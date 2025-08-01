package com.ble_remote_client.client

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.ActivityCompat

class BLEClientService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val reconnectRunnable = object : Runnable {
        override fun run() {
            tryConnectToServer(this@BLEClientService)
            handler.postDelayed(this, 10_000) // Retry every 10 seconds
        }
    }
    private var TAG = "BLEClientService"
    private lateinit var bleClient: BLEClient
    private val NOTIFICATION_ID = 7 // Or any unique integer

    companion object {

        const val ACTION_START_CLIENT = "start_srv"
        const val ACTION_STOP_CLIENT = "stop_srv"

        val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> get() = _isConnected
        val _isClientServiceRunning = MutableStateFlow(false)
        val isClientServiceRunning: StateFlow<Boolean> get() = _isClientServiceRunning
        val _lastConnectionTime = MutableStateFlow<String>("Never")
        val lastConnectionTime: StateFlow<String> get() = _lastConnectionTime
        private const val CHANNEL_ID = "BluetoothClientChannel"

    }

    override fun onCreate() {
        super.onCreate()

//        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ||
//            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
//            bleClient = BLEClient(this) // Initialize here
//        } else {
//            PackageManager.PERMISSION_GRANTED
//        }

        bleClient = BLEClient(this) // Initialize here

//        if (permission == PackageManager.PERMISSION_GRANTED) {
//            startInForeground()

//            serverLogsState.value = "Opening BT server...\n"
//            startServer()
//            _isServerRunning.value = true
//        } else {
//            serverLogsState.value = "Missing connect permission\n"
//            stopSelf()
//        }
//        startForegroundNotification()
//        handler.post(reconnectRunnable)
    }

    @SuppressLint("MissingPermission")
    private fun tryConnectToServer(context: Context) {
        Log.d(TAG, "Attempting to connect to BLE server")

        bleClient.startScan(
            onDeviceFound = { result ->
                val device = result.device
                Log.d(TAG, "Found device: ${device.name} - ${device.address}")
                if (device.name == "ESP32_GATT_SERVER") {
                    bleClient.connectToDevice(device)
//                    bleClient.stopScan()
                }
            },
            onScanFailed = { error ->
                Log.e(TAG, "BLE Scan failed: $error")
            }
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(reconnectRunnable)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            return START_NOT_STICKY
        }

        // Always start foreground first
        startInForeground()

        when (intent.action) {
            ACTION_START_CLIENT -> {
                tryConnectToServer(this)
                _isClientServiceRunning.value = true
            }

            ACTION_STOP_CLIENT -> {
                if (::bleClient.isInitialized) {
                    bleClient.stopScan() // <-- Stop scanning here
                }
                _isClientServiceRunning.value = false
                stopSelf()
            }

            else -> throw IllegalArgumentException("Unknown action")
        }

        return START_STICKY
    }

    private fun startInForeground() {
        createNotificationChannel()

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.stat_notify_sync) // Fallback
            .setContentTitle("Bluetooth Client Service")
            .setContentText("Running...")
            .setPriority(NotificationCompat.PRIORITY_LOW) // Optional but useful
            .setCategory(NotificationCompat.CATEGORY_SERVICE) // Helps on some versions
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                1,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(1, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Bluetooth Client Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }
}