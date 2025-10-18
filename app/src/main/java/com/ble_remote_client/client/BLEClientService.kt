package com.ble_remote_client.client

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.LocationManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ble_remote_client.AppConfig
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import android.bluetooth.BluetoothManager

class BLEClientService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var TAG = "BLEClientService"
    private lateinit var bleClient: BLEClient
    private val NOTIFICATION_ID = 7

    private var isProcessingStartClient: Boolean = false // Flag to prevent re-entrancy
    private var isAutoReconnectEnabled = false

    private val reconnectRunnable = Runnable {
        if (isAutoReconnectEnabled) {
            Log.i(TAG, "Executing scheduled reconnection attempt.")
            tryConnectToServer(this)
        }
    }

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
        private const val PREFS_NAME = "button_config_prefs" // Ensure this matches SettingsActivity
        private const val KEY_APP_CONFIG = "appConfig"       // Key for the AppConfig JSON
        private const val RECONNECT_DELAY_MS = 5000L
    }

    override fun onCreate() {
        super.onCreate()
        bleClient = BLEClient(this)
        // Set the callback that BLEClient will invoke on any disconnection.
        bleClient.onDisconnect = {
            // This callback may be invoked from a background thread, so post to the main handler.
            handler.post {
                _isConnected.value = false
                Log.w(TAG, "onDisconnect callback received from BLEClient.")
                if (isAutoReconnectEnabled) {
                    scheduleReconnect()
                } else {
                    Log.i(TAG, "Auto-reconnect is disabled, not attempting to reconnect.")
                }
            }
        }
        Log.d(TAG, "BLEClientService onCreate: bleClient initialized and onDisconnect callback is set.")
    }

    @SuppressLint("MissingPermission")
    private fun tryConnectToServer(context: Context) {
        Log.d(TAG, "Attempting to connect to BLE server")

        val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val appConfigJson = prefs.getString(KEY_APP_CONFIG, null)
        var targetDeviceNameToConnect: String?
        var targetDeviceMacToConnect: String?

        if (appConfigJson != null) {
            try {
                val appConfig = Gson().fromJson(appConfigJson, AppConfig::class.java)
                targetDeviceNameToConnect = appConfig.targetDeviceName
                targetDeviceMacToConnect = appConfig.targetDeviceMacAddress

                if (targetDeviceMacToConnect.isNullOrEmpty()) {
                    Log.e(TAG, "Target device MAC address from AppConfig is null or empty. Cannot connect.")
                    isProcessingStartClient = false
                    stopSelf()
                    return
                }
                Log.i(TAG, "Target device name loaded: '$targetDeviceNameToConnect'")
                Log.i(TAG, "Target device MAC loaded: '$targetDeviceMacToConnect'")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse AppConfig from JSON", e)
                isProcessingStartClient = false
                stopSelf()
                return
            }
        } else {
            Log.w(TAG, "AppConfig JSON not found in SharedPreferences. Cannot determine target device.")
            isProcessingStartClient = false
            stopSelf()
            return
        }

        if (!::bleClient.isInitialized) {
            Log.e(TAG, "tryConnectToServer: bleClient not initialized! Re-initializing.")
            bleClient = BLEClient(this)
        }

        bleClient.connectByMac(targetDeviceMacToConnect)
    }

     override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "BLEClientService onDestroy.")
        cancelReconnect() // Cancel any pending reconnects
        if (::bleClient.isInitialized) {
            bleClient.disconnect()
        }
        _isClientServiceRunning.value = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        if (intent?.action == null) {
            Log.w(TAG, "onStartCommand received a null intent or action. Returning START_NOT_STICKY.")
            return START_NOT_STICKY
        }

        Log.d(TAG, "onStartCommand received action: ${intent.action}")

        when (intent.action) {
            ACTION_START_CLIENT -> {
                Log.i(TAG, "ACTION_START_CLIENT received.")
                isProcessingStartClient = true
                isAutoReconnectEnabled = true // Enable the reconnect loop

                handler.post {
                    if (!::bleClient.isInitialized) {
                        Log.w(TAG, "ACTION_START_CLIENT: bleClient was NOT initialized. Initializing now.")
                        bleClient = BLEClient(this)
                    }

                    Log.d(TAG, "ACTION_START_CLIENT: Preparing to start/restart BLE operations.")
                    bleClient.disconnect()

                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                        Log.e(TAG, "Missing BLUETOOTH_SCAN")
                    } else {
                        Log.i(TAG, "BLUETOOTH_SCAN permission granted.")
                    }

                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                        Log.e(TAG, "Missing ACCESS_FINE_LOCATION")
                    } else {
                        Log.i(TAG, "ACCESS_FINE_LOCATION permission granted.")
                    }

                    val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
                    if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) && !locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                        Log.e(TAG, "System location is OFF. BLE scanning might not work. Please enable location.")
                    } else {
                        Log.d(TAG, "System location is ON.")
                    }

                    Log.d(TAG, "ACTION_START_CLIENT: Starting BLE operations.")
                    tryConnectToServer(this)
                    _isClientServiceRunning.value = true
                }
            }

            ACTION_STOP_CLIENT -> {
                Log.i(TAG, "ACTION_STOP_CLIENT: Stopping client service.")
                isAutoReconnectEnabled = false // Disable the reconnect loop
                cancelReconnect() // Stop any pending reconnect attempts
                isProcessingStartClient = true
                if (::bleClient.isInitialized) {
                    bleClient.disconnect()
                }
                _isClientServiceRunning.value = false
                _isConnected.value = false
                isProcessingStartClient = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            else -> {
                Log.e(TAG, "Unknown action received: ${intent.action}")
                isProcessingStartClient = false
            }
        }
        return START_STICKY
    }

    private fun scheduleReconnect() {
        cancelReconnect() // Remove any existing callbacks to prevent duplicates
        Log.i(TAG, "Scheduling a reconnection attempt in ${RECONNECT_DELAY_MS}ms.")
        handler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS)
    }

    private fun cancelReconnect() {
        handler.removeCallbacks(reconnectRunnable)
        Log.d(TAG, "Canceled any pending reconnection attempts.")
    }
    private fun startInForeground() {
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.stat_notify_sync)
            .setContentTitle("Bluetooth Client Service")
            .setContentText("BLE client is active.")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            Log.d(TAG, "Service started in foreground.")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground service: ${e.message}", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Bluetooth Client Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
            Log.d(TAG, "Notification channel created/updated.")
        }
    }
}
