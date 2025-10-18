package com.ble_remote_client

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.ble_remote_client.client.ClientUtils

class BluetoothConnectionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BluetoothConnectionRcvr"
        private const val PREFS_NAME = "BleRemoteClientPrefs" // Same as in MainActivity
        private const val KEY_AUTO_CONNECT_DEVICE_ADDRESS =
            "autoConnectDeviceAddress" // Same as in MainActivity
    }

    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) {
            Log.w(TAG, "Context or Intent is null, cannot proceed.")
            return
        }

        val action = intent.action
        Log.d(TAG, "Received action: $action")

        if (BluetoothDevice.ACTION_ACL_CONNECTED == action) {
//            // Check for BLUETOOTH_CONNECT permission before accessing device details if on Android S+
//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
//                if (ContextCompat.checkSelfPermission(
//                        context,
//                        Manifest.permission.BLUETOOTH_CONNECT
//                    ) != PackageManager.PERMISSION_GRANTED
//                ) {
//                    Log.w(
//                        TAG,
//                        "BLUETOOTH_CONNECT permission not granted. Cannot process ACL connected event."
//                    )
//                    // Cannot request permission from a BroadcastReceiver, this should be handled by the Activity.
//                    return
//                }
//                //Check for BLUETOOTH_SCAN permission before accessing device details if on Android R+
//                if (ContextCompat.checkSelfPermission(
//                        context,
//                        Manifest.permission.BLUETOOTH_SCAN
//                    ) != PackageManager.PERMISSION_GRANTED
//                ) {
//                    Log.w(
//                        TAG,
//                        "BLUETOOTH_SCAN permission not granted. Cannot process ACL connected event."
//                    )
//                    // Cannot request permission from a BroadcastReceiver, this should be handled by the Activity.
//                    return
//                }
                //Check for ACCESS_FINE_LOCATION permission before accessing device details if on Android R+
//                if (ContextCompat.checkSelfPermission(
//                        context,
//                        Manifest.permission.ACCESS_FINE_LOCATION
//                    ) != PackageManager.PERMISSION_GRANTED
//                ) {
//                    Log.w(
//                        TAG,
//                        "ACCESS_FINE_LOCATION permission not granted. Cannot process ACL connected event."
//                    )
//                    // Cannot request permission from a BroadcastReceiver, this should be handled by the Activity.
//                    return
//                }
//            }

            val device: BluetoothDevice? =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(
                        BluetoothDevice.EXTRA_DEVICE,
                        BluetoothDevice::class.java
                    )
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }

            if (device == null) {
                Log.w(TAG, "Connected device is null.")
                return
            }

            Log.i(TAG, "Device connected: ${device.name ?: "Unknown Device"} - ${device.address}")

            val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val autoConnectAddress = sharedPrefs.getString(KEY_AUTO_CONNECT_DEVICE_ADDRESS, null)

            if (autoConnectAddress != null && autoConnectAddress == device.address) {
                Log.i(
                    TAG,
                    "Connected device ${device.address} matches auto-connect device. Starting client service."
                )
                ClientUtils.startClientService(context)
            } else {
                Log.d(
                    TAG,
                    "Connected device ${device.address} does not match auto-connect address ($autoConnectAddress)."
                )
            }
        } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED == action) {
            // Check for BLUETOOTH_CONNECT permission before accessing device details if on Android S+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    Log.w(
                        TAG,
                        "BLUETOOTH_CONNECT permission not granted. Cannot process ACL disconnected event."
                    )
                    return
                }
            }

            val device: BluetoothDevice? =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(
                        BluetoothDevice.EXTRA_DEVICE,
                        BluetoothDevice::class.java
                    )
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }

            if (device == null) {
                Log.w(TAG, "Disconnected device is null.")
                return
            }

            Log.i(
                TAG,
                "Device disconnected: ${device.name ?: "Unknown Device"} - ${device.address}"
            )

            val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val autoConnectAddress = sharedPrefs.getString(KEY_AUTO_CONNECT_DEVICE_ADDRESS, null)

            if (autoConnectAddress != null && autoConnectAddress == device.address) {
                Log.i(
                    TAG,
                    "Disconnected device ${device.address} matches auto-connect device. Stopping client service."
                )
                ClientUtils.stopClientService(context)
            } else {
                Log.d(
                    TAG,
                    "Disconnected device ${device.address} does not match auto-connect address ($autoConnectAddress)."
                )
            }
        }
    }
}