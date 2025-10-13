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

    private var bluetoothGatt: BluetoothGatt? = null

    private var scanCallback: ScanCallback? = null
    private var targetDevice: BluetoothDevice? = null // This is assigned but not used elsewhere, consider its purpose.
    val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> get() = _isConnected
    val _lastConnectionTime = MutableStateFlow("Never")
    val lastConnectionTime: StateFlow<String> get() = _lastConnectionTime
    private var isScanning = false
    var onDisconnect: (() -> Unit)? = null

    // Consider moving UUIDs to a dedicated object/file like BLEUUIDs.kt if not already done
    val CLIENT_CHARACTERISTIC_CONFIG_UUID = "00002902-0000-1000-8000-00805f9b34fb"


    companion object {
        private const val TAG = "BLEClient"
        private const val SCAN_PERIOD: Long = 10000 // Stops scanning after 10 seconds.
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
            bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            bluetoothGatt
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
        onDisconnect?.invoke()
        bluetoothGatt?.disconnect()
    }


    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            val deviceAddress = gatt?.device?.address ?: "Unknown device"
            Log.i(TAG, "onConnectionStateChange for $deviceAddress: newState=$newState, status=$status")

            if (gatt != bluetoothGatt && bluetoothGatt != null) {
                Log.w(TAG, "onConnectionStateChange received for a different GATT instance. Current GATT: ${bluetoothGatt?.device?.address}, Received for: ${gatt?.device?.address}. Ignoring stale callback.")
                return
            }


            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Connected to GATT server: $deviceAddress")
                if (gatt != null) {
                    if (this@BLEClient.bluetoothGatt != gatt) { 
                        this@BLEClient.bluetoothGatt?.close() 
                        this@BLEClient.bluetoothGatt = gatt
                    }
                    _isConnected.value = true
                    updateConnectionStatus(true)
                    Log.i(TAG, "Attempting to discover services for $deviceAddress...")
                    this@BLEClient.bluetoothGatt?.discoverServices()
                } else {
                    Log.w(TAG, "GATT instance is null in onConnectionStateChange (CONNECTED). Cannot proceed.")
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.w(TAG, "Disconnected from GATT server: $deviceAddress. Status: $status")
                _isConnected.value = false
                updateConnectionStatus(false)
                onDisconnect?.invoke()
                gatt?.close()
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
                val characteristic = gatt?.getService(BLEUUIDs.SERVICE_UUID)?.getCharacteristic(BLEUUIDs.CHAR_UUID)
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
            super.onCharacteristicChanged(gatt, characteristic, value)
             if (gatt != bluetoothGatt) {
                 Log.w(TAG, "onCharacteristicChanged received for a different GATT instance. Ignoring.")
                 return
            }
            val message = value.toString(Charsets.UTF_8)
            Log.i(TAG, "Message received on ${characteristic.uuid}: $message from ${gatt.device.address}")
            val haHandler = HomeAssistantCommandHandler(context)
            haHandler.handleBleCommand(message)
        }

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
            ?.getService(BLEUUIDs.SERVICE_UUID)
            ?.getCharacteristic(BLEUUIDs.CHAR_UUID)

        char?.let {
            it.value = message.toByteArray(Charsets.UTF_8)
            if (bluetoothGatt?.writeCharacteristic(it) == true) {
                 Log.i(TAG, "Message sent: $message")
            } else {
                 Log.w(TAG, "Failed to send message: $message")
            }
        }
    }

    private fun updateConnectionStatus(isConnected: Boolean) {
        if (isConnected) {
            val currentTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            _lastConnectionTime.value = currentTime
        }
    }
}
