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
import com.example.yourapp.HomeAssistantCommandHandler // Ensure this import is correct for your project
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

    private var scanCallback: ScanCallback? = null
    private var targetDevice: BluetoothDevice? = null // This is assigned but not used elsewhere, consider its purpose.
    val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> get() = _isConnected
    val _lastConnectionTime = MutableStateFlow("Never")
    val lastConnectionTime: StateFlow<String> get() = _lastConnectionTime
    private var isScanning = false

    // Consider moving UUIDs to a dedicated object/file like BLEUUIDs.kt if not already done
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
        scanTimeoutMillis: Long = SCAN_PERIOD, // scanTimeoutMillis is defined but not used to stop scan after timeout
    ) {
        if (bluetoothAdapter == null || bluetoothLeScanner == null) {
            Log.w(TAG, "Bluetooth not supported or adapter not initialized.")
            onScanFailed(ScanCallback.SCAN_FAILED_INTERNAL_ERROR)
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            Log.w(TAG, "Bluetooth is not enabled.")
            onScanFailed(ScanCallback.SCAN_FAILED_INTERNAL_ERROR) // Consider a more specific error code if available
            return
        }

        // Stop any existing scan before starting a new one
        if (isScanning) {
            stopScan()
        }
        // Disconnect if already connected to a device
        if (_isConnected.value) {
            Log.d(TAG, "Already connected. Disconnecting before starting new scan.")
            disconnect() // Ensure previous connection is cleared
        }


        val scanner = bluetoothAdapter.bluetoothLeScanner // Already have bluetoothLeScanner, can use that

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                result?.device?.let { device ->
                    // Simplified: Assuming you want to call onDeviceFound for any device found
                    // And then connectToDevice if it's the target.
                    // The original logic connected within the scan callback which might be okay for one device,
                    // but onDeviceFound suggests a more general callback.

                    // If you have a specific target MAC address, filter here or let the caller (BLEClientService) do it.
                    // For now, let's assume the service handles the decision to connect based on device.name.
                    onDeviceFound(result)

                    // The original code in BLEClientService has:
                    // if (device.name == "ESP32_GATT_SERVER") {
                    //    bleClient.connectToDevice(device)
                    //    bleClient.stopScan()
                    // }
                    // This logic should ideally reside in the service after onDeviceFound.
                    // The BLEClient could just find devices.
                    // For now, I'm removing the automatic connection and stopScan from here to align with
                    // the idea that `startScan` finds devices and `connectToDevice` connects.
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Scan failed: $errorCode")
                onScanFailed(errorCode)
                isScanning = false // Update scanning state
            }
        }

        scanner?.startScan(filters, settings ?: defaultScanSettings(), scanCallback)
        isScanning = true
        Log.d(TAG, "Started scanning")
        // If scanTimeoutMillis is intended to stop the scan, you'd need a Handler to post a delayed stopScan runnable.
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (isScanning && bluetoothLeScanner != null && scanCallback != null) {
            try {
                bluetoothLeScanner.stopScan(scanCallback)
                Log.d(TAG, "Scan stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping scan: ${e.message}")
            } finally {
                scanCallback = null
                isScanning = false
                _isConnected.value = false
            }
        } else {
            Log.d(TAG, "Scan not active or scanner/callback not available.")
        }
    }

    private fun defaultScanSettings(): ScanSettings {
        return ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice) {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.w(TAG, "Bluetooth adapter not initialized or not enabled.")
            return
        }
        // If already connected to this device or another, disconnect first
        if (bluetoothGatt != null) {
            Log.d(TAG, "connectToDevice: GATT instance exists. Address: ${bluetoothGatt?.device?.address}, New Device: ${device.address}")
            if (bluetoothGatt?.device?.address == device.address && _isConnected.value) {
                Log.i(TAG, "Already connected to ${device.address}. No action needed.")
                return
            }
            Log.d(TAG, "Disconnecting existing GATT connection before connecting to new device.")
            disconnect() // Disconnect previous
        }

        Log.i(TAG, "Connecting to device: ${device.name} (${device.address})")
        bluetoothGatt = device.connectGatt(context, false, gattCallback)
    }

    @SuppressLint("MissingPermission")
    fun connectByMac(mac: String): BluetoothGatt? {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter

        if (adapter == null) {
            Log.e("BLE", "Bluetooth not supported on this device")
            return null
        }

        return try {
            val device = adapter.getRemoteDevice(mac)
            Log.i("BLE", "Connecting to device $mac...")
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: IllegalArgumentException) {
            Log.e("BLE", "Invalid MAC address: $mac", e)
            null
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        if (bluetoothGatt == null) {
            Log.d(TAG, "disconnect: No GATT connection to disconnect.")
            return
        }
        Log.i(TAG, "Disconnecting from GATT server: ${bluetoothGatt?.device?.address}")
        bluetoothGatt?.disconnect()
        // `close()` should ideally be called after `onConnectionStateChange` reports STATE_DISCONNECTED.
        // For an explicit disconnect, some call it here. If issues arise, manage close() in the callback.
        // bluetoothGatt?.close() // See note below
        // bluetoothGatt = null // Will be set to null in onConnectionStateChange or after close()
        // _isConnected.value = false // This will be updated by onConnectionStateChange
    }


    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            val deviceAddress = gatt?.device?.address ?: "Unknown device"
            Log.i(TAG, "onConnectionStateChange for $deviceAddress: newState=$newState, status=$status")

            if (gatt != bluetoothGatt && bluetoothGatt != null) {
                Log.w(TAG, "onConnectionStateChange received for a different GATT instance. Current GATT: ${bluetoothGatt?.device?.address}, Received for: ${gatt?.device?.address}. Ignoring stale callback.")
                // To prevent issues with stale callbacks after a quick disconnect and reconnect,
                // you might close the 'gatt' from this callback if it's not the current one.
                // gatt?.close()
                return
            }


            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Connected to GATT server: $deviceAddress")
                // Store the gatt instance if it's a new connection
                // Ensure gatt is not null before proceeding
                if (gatt != null) {
                    if (this@BLEClient.bluetoothGatt != gatt) { // Check if this is a new gatt instance
                        this@BLEClient.bluetoothGatt?.close() // Close the old one if any
                        this@BLEClient.bluetoothGatt = gatt
                    }
                    _isConnected.value = true
                    updateConnectionStatus(true)
                    Log.i(TAG, "Attempting to discover services for $deviceAddress...")
                    // Use the current gatt instance that was confirmed to be non-null and assigned
                    this@BLEClient.bluetoothGatt?.discoverServices()
                } else {
                    Log.w(TAG, "GATT instance is null in onConnectionStateChange (CONNECTED). Cannot proceed.")
                    // Optionally, handle this scenario, e.g., by attempting to disconnect or clean up.
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.w(TAG, "Disconnected from GATT server: $deviceAddress. Status: $status")
                _isConnected.value = false
                updateConnectionStatus(false)
                gatt?.close() // Close GATT client.
                //Call stopClientService
                ClientUtils.stopClientService(context)
                // Clear our reference only if it's the one we were using and it matches the gatt from the callback
                if (this@BLEClient.bluetoothGatt == gatt) {
                    this@BLEClient.bluetoothGatt = null
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (gatt != bluetoothGatt) {
                 Log.w(TAG, "onServicesDiscovered received for a different GATT instance. Ignoring.")
                 return
            }
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Services discovered for ${gatt?.device?.address}")
                // ... (rest of your onServicesDiscovered logic)
                // Example: Enable notifications for a characteristic
                val characteristic = gatt?.getService(BLEUUIDs.SERVICE_UUID)?.getCharacteristic(BLEUUIDs.CHAR_UUID) // Ensure BLEUUIDs is correctly defined
                if (characteristic != null) {
                    if (gatt?.setCharacteristicNotification(characteristic, true) == true) {
                        Log.i(TAG, "Successfully requested notification for ${characteristic.uuid}")
                        val descriptor = characteristic.getDescriptor(UUID.fromString(CLIENT_CHARACTERISTIC_CONFIG_UUID))
                        if (descriptor != null) {
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            if (gatt.writeDescriptor(descriptor) == true) {
                                Log.i(TAG, "Successfully wrote CCCD descriptor for ${characteristic.uuid}")
                            } else {
                                Log.w(TAG, "Failed to write CCCD descriptor for ${characteristic.uuid}")
                            }
                        } else {
                            Log.w(TAG, "CCCD descriptor not found for ${characteristic.uuid}")
                        }
                    } else {
                        Log.w(TAG, "Failed to set notification for ${characteristic.uuid}")
                    }
                } else {
                    Log.w(TAG, "Target characteristic not found for notifications.")
                }
            } else {
                Log.w(TAG, "Service discovery failed with status $status for ${gatt?.device?.address}")
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            super.onCharacteristicChanged(gatt, characteristic, value) // Use the new signature
             if (gatt != bluetoothGatt) {
                 Log.w(TAG, "onCharacteristicChanged received for a different GATT instance. Ignoring.")
                 return
            }
            val message = value.toString(Charsets.UTF_8)
            Log.i(TAG, "Message received on ${characteristic.uuid}: $message from ${gatt.device.address}")
            val haHandler = HomeAssistantCommandHandler(context) // Ensure this class is correctly defined and imported
            haHandler.handleBleCommand(message)
        }
        // Make sure to use the correct override for onCharacteristicChanged
        // For API 33+ it's onCharacteristicChanged(gatt, characteristic, value)
        // For older it's onCharacteristicChanged(gatt, characteristic) and then get value from characteristic.value

        override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
             if (gatt != bluetoothGatt) {
                 Log.w(TAG, "onCharacteristicWrite received for a different GATT instance. Ignoring.")
                 return
            }
            Log.i(TAG, "Write completed with status $status for characteristic ${characteristic?.uuid} on ${gatt?.device?.address}")
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            super.onDescriptorWrite(gatt, descriptor, status)
            if (gatt != bluetoothGatt) {
                 Log.w(TAG, "onDescriptorWrite received for a different GATT instance. Ignoring.")
                 return
            }
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Descriptor ${descriptor?.uuid} written successfully for ${gatt?.device?.address}")
            } else {
                Log.w(TAG, "Descriptor ${descriptor?.uuid} write failed with status $status for ${gatt?.device?.address}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun sendMessage(message: String) {
        if (bluetoothGatt == null || !_isConnected.value) {
            Log.w(TAG, "Not connected to any device. Cannot send message.")
            return
        }
        val char = bluetoothGatt
            ?.getService(BLEUUIDs.SERVICE_UUID) // Ensure BLEUUIDs is correctly defined
            ?.getCharacteristic(BLEUUIDs.CHAR_UUID)

        char?.let {
            it.value = message.toByteArray(Charsets.UTF_8)
            // Consider using it.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT or WRITE_TYPE_NO_RESPONSE
            if (bluetoothGatt?.writeCharacteristic(it) == true) {
                 Log.i(TAG, "Message sent: $message")
            } else {
                 Log.w(TAG, "Failed to send message: $message")
            }
        } ?: Log.w(TAG, "Characteristic not found for sending message.")
    }

    private fun updateConnectionStatus(connected: Boolean) {
        // _isConnected.value is already being set directly in onConnectionStateChange
        // This function is mainly for lastConnectionTime
        if (connected) {
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            _lastConnectionTime.value = time
        }
        // If disconnected, _lastConnectionTime remains as is.
        // _isConnected.value is set in onConnectionStateChange
    }
}
