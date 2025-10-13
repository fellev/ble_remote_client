package com.ble_remote_client

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.ble_remote_client.client.ClientUtils
import com.ble_remote_client.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var chooseAutoConnectDeviceButton: Button
    private lateinit var connectionStatus: TextView
    private lateinit var autoConnectDeviceStatusTextView: TextView // Added

    private var bluetoothAdapter: BluetoothAdapter? = null

    companion object {
        private const val REQUEST_BLUETOOTH_CONNECT = 1
        private const val PREFS_NAME = "BleRemoteClientPrefs"
        private const val KEY_AUTO_CONNECT_DEVICE_ADDRESS = "autoConnectDeviceAddress"
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.appBarMain.toolbar)

        val navController = findNavController(R.id.nav_host_fragment_content_main)
        if (findViewById<androidx.drawerlayout.widget.DrawerLayout>(R.id.drawer_layout) != null) {
            appBarConfiguration = AppBarConfiguration(
                setOf(R.id.nav_home, R.id.nav_gallery, R.id.nav_slideshow),
                binding.drawerLayout
            )
            setupActionBarWithNavController(navController, appBarConfiguration)
            binding.navView?.setupWithNavController(navController)
        } else {
            appBarConfiguration = AppBarConfiguration(navController.graph)
            setupActionBarWithNavController(navController, appBarConfiguration)
        }

        connectButton = findViewById(R.id.connect_button)
        disconnectButton = findViewById(R.id.disconnect_button)
        chooseAutoConnectDeviceButton = findViewById(R.id.choose_auto_connect_device_button)
        connectionStatus = findViewById(R.id.connection_status_text_view)
        autoConnectDeviceStatusTextView = findViewById(R.id.auto_connect_device_status_text_view) // Added

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth is not available on this device", Toast.LENGTH_LONG).show()
        }

        requestNotificationPermissionIfNeeded()
        requestBluetoothPermissionsIfNeeded()

        connectButton.setOnClickListener {
            ClientUtils.startClientService(this)
        }

        disconnectButton.setOnClickListener {
            ClientUtils.stopClientService(this)
        }

        chooseAutoConnectDeviceButton.setOnClickListener {
            showPairedDevicesDialog()
        }
    }

    override fun onResume() {
        super.onResume()
        updateAutoConnectDeviceStatus() // Added call
    }

    @SuppressLint("MissingPermission") // Permissions checked within the function or before calling
    private fun updateAutoConnectDeviceStatus() {
        val sharedPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val deviceAddress = sharedPrefs.getString(KEY_AUTO_CONNECT_DEVICE_ADDRESS, null)

        if (deviceAddress != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                autoConnectDeviceStatusTextView.text = "Auto-connect: $deviceAddress (Permission needed to see name)"
                return
            }
            try {
                val device = bluetoothAdapter?.getRemoteDevice(deviceAddress)
                if (device != null) {
                    val deviceName = device.name ?: "Unknown Device"
                    autoConnectDeviceStatusTextView.text = "Auto-connect: $deviceName ($deviceAddress)"
                } else {
                    autoConnectDeviceStatusTextView.text = "Auto-connect: Device not found ($deviceAddress)"
                }
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "Invalid Bluetooth address: $deviceAddress", e)
                autoConnectDeviceStatusTextView.text = "Auto-connect: Invalid address ($deviceAddress)"
            } catch (e: SecurityException) {
                 Log.e(TAG, "SecurityException when getting remote device: $deviceAddress", e)
                 autoConnectDeviceStatusTextView.text = "Auto-connect: Permission issue for $deviceAddress"
            }
        } else {
            autoConnectDeviceStatusTextView.text = "Auto-connect device: Not selected"
        }
    }

    private fun requestBluetoothPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val permissionsToRequest = mutableListOf<String>()
            Log.d(TAG, "Checking Bluetooth permissions for Android S+")

            val connectPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            Log.d(TAG, "BLUETOOTH_CONNECT granted: ${connectPermission == PackageManager.PERMISSION_GRANTED}")
            if (connectPermission != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            }

            val scanPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
            Log.d(TAG, "BLUETOOTH_SCAN granted: ${scanPermission == PackageManager.PERMISSION_GRANTED}")
            if (scanPermission != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            }

            val fineLocationPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            Log.d(TAG, "ACCESS_FINE_LOCATION granted: ${fineLocationPermission == PackageManager.PERMISSION_GRANTED}")
            if (fineLocationPermission != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }

            if (permissionsToRequest.isNotEmpty()) {
                Log.d(TAG, "Requesting permissions: ${permissionsToRequest.joinToString()}")
                ActivityCompat.requestPermissions(
                    this,
                    permissionsToRequest.toTypedArray(),
                    REQUEST_BLUETOOTH_CONNECT
                )
            } else {
                Log.d(TAG, "All required Bluetooth permissions are already granted.")
            }
        } else {
            Log.d(TAG, "Checking Bluetooth permissions for versions older than Android S")
            val fineLocationPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            Log.d(TAG, "ACCESS_FINE_LOCATION granted: ${fineLocationPermission == PackageManager.PERMISSION_GRANTED}")
            if (fineLocationPermission != PackageManager.PERMISSION_GRANTED ) {
                Log.d(TAG, "Requesting permission: ${Manifest.permission.ACCESS_FINE_LOCATION}")
                ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                    REQUEST_BLUETOOTH_CONNECT)
            } else {
                Log.d(TAG, "ACCESS_FINE_LOCATION is already granted.")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun showPairedDevicesDialog() {
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth is not available", Toast.LENGTH_SHORT).show()
            return
        }
        if (!bluetoothAdapter!!.isEnabled) {
            Toast.makeText(this, "Please enable Bluetooth", Toast.LENGTH_SHORT).show()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Bluetooth connect permission not granted", Toast.LENGTH_SHORT).show()
                requestBluetoothPermissionsIfNeeded()
                return
            }
        }

        val pairedDevices: Set<BluetoothDevice>? = bluetoothAdapter!!.bondedDevices
        val deviceList = ArrayList<BluetoothDevice>()
        val deviceListStrings = ArrayList<String>()

        if (pairedDevices?.isNotEmpty() == true) {
            for (device in pairedDevices) {
                deviceList.add(device)
                val deviceName = if (device.name.isNullOrEmpty()) "Unknown Device" else device.name
                deviceListStrings.add("$deviceName - ${device.address}")
            }

            val builder = AlertDialog.Builder(this)
            builder.setTitle("Choose a Paired Device")
            builder.setItems(deviceListStrings.toTypedArray()) { dialog, which ->
                val selectedDevice = deviceList[which]
                val sharedPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                with(sharedPrefs.edit()) {
                    putString(KEY_AUTO_CONNECT_DEVICE_ADDRESS, selectedDevice.address)
                    apply()
                }
                val selectedDeviceName = if (selectedDevice.name.isNullOrEmpty()) selectedDevice.address else selectedDevice.name
                Toast.makeText(this, "Auto-connect device set to: $selectedDeviceName", Toast.LENGTH_LONG).show()
                updateAutoConnectDeviceStatus() // Added call
                dialog.dismiss()
            }
            builder.setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            val dialog = builder.create()
            dialog.show()
        } else {
            Toast.makeText(this, "No paired devices found.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BLUETOOTH_CONNECT) {
            var allRequiredPermissionsGranted = true
            if (grantResults.isEmpty()) {
                allRequiredPermissionsGranted = false
            } else {
                for (i in permissions.indices) {
                    if (grantResults.getOrElse(i) { PackageManager.PERMISSION_DENIED } != PackageManager.PERMISSION_GRANTED) {
                        Log.w(TAG, "Permission denied: ${permissions[i]}")
                        allRequiredPermissionsGranted = false
                        if (permissions[i] == Manifest.permission.BLUETOOTH_CONNECT && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            break
                        }
                        if (permissions[i] == Manifest.permission.ACCESS_FINE_LOCATION && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                            break
                        }
                    }
                }
            }

            if (allRequiredPermissionsGranted) {
                Toast.makeText(this, "Bluetooth permissions granted.", Toast.LENGTH_SHORT).show()
                updateAutoConnectDeviceStatus() // Added call if permissions are granted
            } else {
                connectionStatus.text = "Permission denied: Cannot use Bluetooth features"
                Toast.makeText(this, "Bluetooth permissions are required for auto-connect feature.", Toast.LENGTH_LONG).show()
                updateAutoConnectDeviceStatus() // Update status even if permissions denied (will show address only)
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (!isGranted) {
            Toast.makeText(this, "Notification permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                // Handle the Settings menu item click
                val intent = Intent(this, SettingsActivity::class.java)
                intent.putExtra("button_index", 0) // for Button 1
                startActivity(intent)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}