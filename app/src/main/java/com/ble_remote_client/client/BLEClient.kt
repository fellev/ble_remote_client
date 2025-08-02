package com.ble_remote_client.client

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import com.example.yourapp.HomeAssistantCommandHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class BLEClient(private val context: Context) {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter
    private val bluetoothLeScanner: BluetoothLeScanner? = bluetoothAdapter?.bluetoothLeScanner
    private var bluetoothGatt: BluetoothGatt? = null

    private var scanCallback: ScanCallback? = null // <-- Added
    private var targetDevice: BluetoothDevice? = null
    val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> get() = _isConnected
    val _lastConnectionTime = MutableStateFlow("Never")
    val lastConnectionTime: StateFlow<String> get() = _lastConnectionTime
    private var isScanning = false

    val CLIENT_CHARACTERISTIC_CONFIG_UUID = "00002902-0000-1000-8000-00805f9b34fb"


    companion object {
        private const val TAG = "BLEClient"
        private const val SCAN_PERIOD: Long = 10000 // Stops scanning after 10 seconds.
    }

    @SuppressLint("MissingPermission")
    fun startScan(
        filters: List<ScanFilter>? = null,
        settings: ScanSettings? = null,
        onDeviceFound: (ScanResult) -> Unit,
        onScanFailed: (Int) -> Unit,
        scanTimeoutMillis: Long = SCAN_PERIOD,
    ) {
        if (bluetoothAdapter == null || bluetoothLeScanner == null) {
            Log.w(TAG, "Bluetooth not supported or adapter not initialized.")
            onScanFailed(ScanCallback.SCAN_FAILED_INTERNAL_ERROR)
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            Log.w(TAG, "Bluetooth is not enabled.")
            onScanFailed(ScanCallback.SCAN_FAILED_INTERNAL_ERROR)
            return
        }

        val scanner = bluetoothAdapter.bluetoothLeScanner

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                result?.device?.let { device ->
//                    if (result.scanRecord?.serviceUuids?.any { it.uuid == BLEUUIDs.SERVICE_UUID } == true) {
//                        Log.i(TAG, "Device found: ${device.name} (${device.address})")
//                        onDeviceFound(result)
//                        stopScan()
//                    }
//                    else {
//                        Log.i(TAG, "Device not found: ${device.name} (${device.address}) ${result.scanRecord?.serviceUuids}")
//                    }
                    //Find the target device by MAC
                    if (device.address == "98:A3:16:E2:64:3E") {
                        targetDevice = device
                        Log.i(TAG, "Target device found: ${device.name} (${device.address})")
                        //Print the UUID
//                        Log.i(TAG, "UUID: ${result.scanRecord?.serviceUuids}")

                        // Connect to GATT
                        val gatt = device.connectGatt(context, false, gattCallback)

                        onDeviceFound(result)
                        stopScan()
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Scan failed: $errorCode")
                onScanFailed(errorCode)
            }
        }

        scanner.startScan(filters, settings ?: defaultScanSettings(), scanCallback)
        isScanning = true
        Log.d(TAG, "Started scanning")
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (isScanning && bluetoothLeScanner != null && scanCallback != null) {
            bluetoothLeScanner.stopScan(scanCallback)
            Log.d(TAG, "Scan stopped")
            scanCallback = null
            isScanning = false
        }
    }

    private fun defaultScanSettings(): ScanSettings {
        return ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
    }

    @SuppressLint("MissingPermission")
    internal fun connectToDevice(device: BluetoothDevice) {
        bluetoothGatt = device.connectGatt(context, false, gattCallback)
    }

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Connected to GATT server")
                gatt?.discoverServices()
                _isConnected.value = true
                updateConnectionStatus(true)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.w(TAG, "Disconnected from GATT server")
                _isConnected.value = false
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Services discovered")
                // Use let to safely access services if gatt is not null
                gatt?.getServices()?.let { services -> // services is now a non-nullable List
                    for (service in services) {
                        Log.d(TAG, "Service UUID: ${service.uuid}")
                        // Also handle potentially null characteristics for each service
                        service.characteristics?.let { characteristics -> // characteristics is now non-nullable
                            for (char in characteristics) {
                                Log.d(TAG, "  Characteristic UUID: ${char.uuid}")
                            }
                        }
                    }
                }
                val characteristic = gatt?.getService(BLEUUIDs.SERVICE_UUID)?.getCharacteristic(BLEUUIDs.CHAR_UUID)
                if (characteristic != null) {
                    gatt.setCharacteristicNotification(characteristic, true)

                    val descriptor = characteristic.getDescriptor(UUID.fromString(CLIENT_CHARACTERISTIC_CONFIG_UUID))
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(descriptor)

                    Log.i(TAG, "Notifications enabled")
                }
            } else {
                Log.w(TAG, "Service discovery failed with status $status")
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
            characteristic?.value?.let {
                val message = it.toString(Charsets.UTF_8)
                Log.i(TAG, "Message received: $message")
                val haHandler = HomeAssistantCommandHandler(context)
                haHandler.handleBleCommand(message)
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            Log.i(TAG, "Write completed with status $status")
        }
    }

    @SuppressLint("MissingPermission")
    fun sendMessage(message: String) {
        val char = bluetoothGatt
            ?.getService(BLEUUIDs.SERVICE_UUID)
            ?.getCharacteristic(BLEUUIDs.CHAR_UUID)

        char?.let {
            it.value = message.toByteArray(Charsets.UTF_8)
            bluetoothGatt?.writeCharacteristic(it)
        } ?: Log.w(TAG, "Characteristic not found")
    }

    private fun updateConnectionStatus(connected: Boolean) {
        _isConnected.value = connected
        if (connected) {
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            _lastConnectionTime.value = time
        }
    }
}
