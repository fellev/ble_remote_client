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
        // Constants for SharedPreferences access - align with SettingsActivity
        private const val PREFS_NAME = "button_config_prefs" // Ensure this matches SettingsActivity
        private const val KEY_APP_CONFIG = "appConfig"       // Key for the AppConfig JSON
    }

    override fun onCreate() {
        super.onCreate()
        bleClient = BLEClient(this)
        Log.d(TAG, "BLEClientService onCreate: bleClient initialized.")
    }

    @SuppressLint("MissingPermission")
    private fun tryConnectToServer(context: Context) {
        Log.d(TAG, "Attempting to connect to BLE server")

        val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val appConfigJson = prefs.getString(KEY_APP_CONFIG, null)
        var targetDeviceNameToConnect: String? = null
        // Build filters — prefer service UUID if your ESP advertises it:
        val filters = mutableListOf<ScanFilter>()

        // Option A: filter by 128-bit/service uuid (recommended if ESP advertises it)
//        val svcUuid = ParcelUuid.fromString(BLEUUIDs.SERVICE_UUID_STRING) // <- replace with your SERVICE_UUID
//        val uuidFilter = ScanFilter.Builder()
//            .setServiceUuid(svcUuid)
//            .build()
//        filters.add(uuidFilter)

        // Option B: OR filter by device address (uncomment if you want)
         val addressFilter = ScanFilter.Builder().setDeviceAddress("98:A3:16:E2:64:3E").build()
         filters.add(addressFilter)

        // Scan settings — filtered scan will be allowed even when screen-off
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY) // or BALANCED/LOW_POWER
            .setMatchMode(ScanSettings.MATCH_MODE_STICKY)    // vendor dependent, optional
            .build()

        if (appConfigJson != null) {
            try {
                val appConfig = Gson().fromJson(appConfigJson, AppConfig::class.java)
                targetDeviceNameToConnect = appConfig.targetDeviceName
                if (targetDeviceNameToConnect.isNullOrEmpty()) {
                    Log.w(TAG, "Target device name from AppConfig is null or empty. Cannot connect.")
                    // Optionally, stop the service or inform the user
                    isProcessingStartClient = false // Reset flag
                    stopSelf() // Consider if appropriate
                    return
                }
                Log.i(TAG, "Target device name loaded from AppConfig: '$targetDeviceNameToConnect'")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse AppConfig from JSON", e)
                isProcessingStartClient = false // Reset flag
                stopSelf() // Consider if appropriate
                return
            }
        } else {
            Log.w(TAG, "AppConfig JSON not found in SharedPreferences. Cannot determine target device.")
            isProcessingStartClient = false // Reset flag
            stopSelf() // Consider if appropriate
            return
        }

        if (!::bleClient.isInitialized) {
            Log.e(TAG, "tryConnectToServer: bleClient not initialized! Re-initializing.")
            bleClient = BLEClient(this)
        }

//        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
//        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:scan")
//        wl.acquire(10_000) // release after 10s

//        bleClient.startScan(
//            filters = filters,
//            settings = settings,
//            onDeviceFound = { result ->
//                val device = result.device
//                val deviceName = device.name ?: "Unknown"
//                Log.d(TAG, "Found device: $deviceName - ${device.address}")
//
//                // Check if this is the target device
//                if (targetDeviceNameToConnect != null && deviceName == targetDeviceNameToConnect) {
//                    Log.i(TAG, "Target device '$targetDeviceNameToConnect' found. Connecting...")
//                    bleClient.stopScan() // Stop scanning once target is found
//                    bleClient.connectToDevice(device)
//                }
//            },
//            onScanFailed = { error ->
//                Log.e(TAG, "BLE Scan failed: $error")
//            }
//        )

        bleClient.connectByMac("98:A3:16:E2:64:3E")
    }

     override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "BLEClientService onDestroy.")
        if (::bleClient.isInitialized) {
            bleClient.stopScan()
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
//                if (isProcessingStartClient) {
//                    Log.w(TAG, "ACTION_START_CLIENT: Already processing a start request. Ignoring duplicate.")
//                    return START_STICKY
//                }
                isProcessingStartClient = true

                handler.post {
                    if (!::bleClient.isInitialized) {
                        Log.w(TAG, "ACTION_START_CLIENT: bleClient was NOT initialized. Initializing now.")
                        bleClient = BLEClient(this)
                    }

                    Log.d(TAG, "ACTION_START_CLIENT: Preparing to start/restart BLE operations.")
                    bleClient.stopScan()
                    bleClient.disconnect()

                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                        Log.e(TAG, "Missing BLUETOOTH_SCAN")
                    }
                    else
                    {
                        Log.i(TAG, "BLUETOOTH_SCAN permission granted.")
                    }

                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                        Log.e(TAG, "Missing ACCESS_FINE_LOCATION")
                    }
                    else
                    {
                        Log.i(TAG, "ACCESS_FINE_LOCATION permission granted.")
                    }

                    // Check if location is enabled on the device
                    val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
                    if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) && !locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                        Log.e(TAG, "System location is OFF. BLE scanning might not work. Please enable location.")
                        // Optionally, you can stop the service or notify the user through other means.
                    } else {
                        Log.e(TAG, "System location is ON.")
                    }



                    Log.d(TAG, "ACTION_START_CLIENT: Starting BLE operations.")
                    tryConnectToServer(this)
                    _isClientServiceRunning.value = true

                }
            }

            ACTION_STOP_CLIENT -> {
                Log.i(TAG, "ACTION_STOP_CLIENT: Stopping client service.")
                isProcessingStartClient = true // Prevent start during stop
                if (::bleClient.isInitialized) {
                    bleClient.stopScan()
                    bleClient.disconnect()
                }
                _isClientServiceRunning.value = false
                _isConnected.value = false // Explicitly set connection state
                isProcessingStartClient = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            else -> {
                Log.e(TAG, "Unknown action received: ${intent.action}")
                isProcessingStartClient = false // Reset if unknown action
            }
        }
        return START_STICKY
    }

    private fun startBleScan(){

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
