package com.example.bt_president_game

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.util.Log
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.bt_president_game.databinding.ActivityMainBinding
import com.example.bt_president_game.ui.GameActivity
import com.example.bt_president_game.viewmodels.MainViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }    
    
    private val bluetoothEnableResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            makeDeviceDiscoverable()
        } else {
            Toast.makeText(this, "Bluetooth is required for this app", Toast.LENGTH_LONG).show()
        }
    }
    
    private val bluetoothDiscoverableResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // result.resultCode contains the duration the device will be discoverable, or
        // RESULT_CANCELED if the user rejected discoverable mode
        if (result.resultCode > 0) {
            Log.d("MainActivity", "Device will be discoverable for ${result.resultCode} seconds")
            setupBluetoothFunctionality()
        } else {
            Log.d("MainActivity", "User declined to make device discoverable. Proceeding anyway.")
            setupBluetoothFunctionality()
        }
    }
    
    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allPermissionsGranted = permissions.entries.all { it.value }
        if (allPermissionsGranted) {
            enableBluetooth()
        } else {
            Toast.makeText(
                this,
                "All permissions are required for this app to work properly",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        initViews()
        checkBluetoothPermissions()
        observeViewModel()
    }

    private fun initViews() {
        binding.buttonCreateGame.setOnClickListener {
            viewModel.startHostingGame()
        }
        
        binding.buttonJoinGame.setOnClickListener {
            viewModel.startDiscovery()
        }
    }
    
    private fun checkBluetoothPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        // Add Bluetooth permissions based on Android version
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // For Android 12 and higher
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
        } else {
            // For Android 11 and lower
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_ADMIN)
            }
        }

        // Location permissions are required for Bluetooth scanning on all Android versions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        
        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            enableBluetooth()
        }
    }
      private fun enableBluetooth() {
        bluetoothAdapter?.let {
            if (!it.isEnabled) {
                val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                if (ActivityCompat.checkSelfPermission(
                        this,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    bluetoothEnableResultLauncher.launch(enableBtIntent)
                }
            } else {
                makeDeviceDiscoverable()
            }
        } ?: run {
            Toast.makeText(this, "This device doesn't support Bluetooth", Toast.LENGTH_LONG).show()
            finish()
        }
    }
    
    private fun makeDeviceDiscoverable() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_ADVERTISE
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            // Make device discoverable for 300 seconds (5 minutes)
            val discoverableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
            discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
            
            // Register for the result only if we have the right permission
            bluetoothDiscoverableResultLauncher.launch(discoverableIntent)
        } else {
            // If we don't have permission, just proceed with setup
            setupBluetoothFunctionality()
        }
    }
    
    private fun setupBluetoothFunctionality() {
        Log.d("MainActivity", "Setting up Bluetooth functionality")
        Log.d("MainActivity", "Bluetooth adapter name: ${bluetoothAdapter?.name}, address: ${bluetoothAdapter?.address}")
        Log.d("MainActivity", "Discoverable: ${bluetoothAdapter?.scanMode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE}")
        
        viewModel.initializeBluetooth(bluetoothAdapter!!)
    }
    
    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.gameCreatedEvent.collect {
                val intent = Intent(this@MainActivity, GameActivity::class.java).apply {
                    putExtra(GameActivity.EXTRA_IS_HOST, true)
                }
                startActivity(intent)
            }
        }
        
        lifecycleScope.launch {
            viewModel.foundDevicesEvent.collect { devices ->
                // Show dialog to select device from list
                if (devices.isNotEmpty()) {
                    showSelectDeviceDialog(devices)
                }
            }
        }
        
        lifecycleScope.launch {
            viewModel.connectedToGameEvent.collect { deviceName ->
                val intent = Intent(this@MainActivity, GameActivity::class.java).apply {
                    putExtra(GameActivity.EXTRA_IS_HOST, false)
                    putExtra(GameActivity.EXTRA_HOST_DEVICE_NAME, deviceName)
                }
                startActivity(intent)
            }
        }
        
        lifecycleScope.launch {
            viewModel.errorEvent.collect { errorMessage ->
                Toast.makeText(this@MainActivity, errorMessage, Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun showSelectDeviceDialog(devices: List<Pair<String, String>>) {
        // Convert devices list to arrays for the dialog
        val deviceNames = devices.map { it.first }.toTypedArray()
        val deviceAddresses = devices.map { it.second }.toTypedArray()
        
        // Show dialog to select a device
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Select a device")
            .setItems(deviceNames) { _, which ->
                val selectedDeviceAddress = deviceAddresses[which]
                viewModel.connectToDevice(selectedDeviceAddress)
            }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .setCancelable(true)
            .show()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        viewModel.cleanup()
    }
}
