package com.example.bt_president_game.viewmodels

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.bt_president_game.data.BluetoothService
import com.example.bt_president_game.data.GameRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gameRepository: GameRepository
) : ViewModel() {

    companion object {
        private const val TAG = "MainViewModel"
        private const val SERVICE_NAME = "BT_PRESIDENT_GAME"
        private val SERVICE_UUID = UUID.fromString("fa87c0d0-afac-11de-8a39-0800200c9a66") // Unique UUID for our app
    }

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var discoveryReceiver: BroadcastReceiver? = null
    private var serverSocket: BluetoothServerSocket? = null
    private var isDiscovering = false
    private val discoveredDevices = mutableMapOf<String, Pair<String, String>>()
    
    private val _gameCreatedEvent = MutableSharedFlow<Unit>()
    val gameCreatedEvent: SharedFlow<Unit> = _gameCreatedEvent
    
    private val _foundDevicesEvent = MutableSharedFlow<List<Pair<String, String>>>()
    val foundDevicesEvent: SharedFlow<List<Pair<String, String>>> = _foundDevicesEvent
    
    private val _connectedToGameEvent = MutableSharedFlow<String>()
    val connectedToGameEvent: SharedFlow<String> = _connectedToGameEvent
    
    private val _errorEvent = MutableSharedFlow<String>()
    val errorEvent: SharedFlow<String> = _errorEvent

    fun initializeBluetooth(adapter: BluetoothAdapter) {
        Log.d(TAG, "Initializing Bluetooth adapter: ${adapter.name}, ${adapter.address}")
        Log.d(TAG, "Adapter state: ${adapter.state}, enabled: ${adapter.isEnabled}, scanning: ${adapter.isDiscovering}")
        Log.d(TAG, "Discoverable: ${adapter.scanMode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE}")
        
        bluetoothAdapter = adapter
        
        try {
            gameRepository.initializeBluetooth(adapter)
            Log.d(TAG, "Bluetooth adapter successfully initialized in GameRepository")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Bluetooth adapter in GameRepository", e)
            viewModelScope.launch {
                _errorEvent.emit("Failed to initialize Bluetooth: ${e.message}")
            }
        }
    }

    fun startHostingGame() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                gameRepository.initializeGameAsHost()
                Log.d(TAG, "Game initializing as host")
                // Start accepting connections in the background
                val success = gameRepository.startHostingGame()
                Log.d(TAG, "Game hosting started. Status: $success")
                if (success) {
                    Log.d(TAG, "Hosting game started successfully")
                    _gameCreatedEvent.emit(Unit)
                } else {
                    _errorEvent.emit("Failed to start hosting game")
                    Log.e(TAG, "Failed to start hosting game")
                }
            } catch (e: IOException) {
                _errorEvent.emit("Failed to create game: ${e.message}")
                Log.e(TAG, "Error starting server socket", e)
            }
        }
    }    fun startDiscovery() {
        Log.d(TAG, "Starting Bluetooth discovery")
        
        val bt = bluetoothAdapter
        if (bt == null) {
            Log.e(TAG, "Bluetooth adapter not initialized")
            viewModelScope.launch {
                _errorEvent.emit("Bluetooth adapter not initialized")
            }
            return
        }
        
        if (isDiscovering) {
            Log.d(TAG, "Discovery already in progress, canceling previous discovery")
            bt.cancelDiscovery()
        }
        
        isDiscovering = true
        discoveredDevices.clear()
        Log.d(TAG, "Cleared previous discovered devices")
        
        // First, add any already paired devices to the list
        try {
            val pairedDevices = bt.bondedDevices
            if (pairedDevices.isNotEmpty()) {
                Log.d(TAG, "Found ${pairedDevices.size} paired devices")
                for (device in pairedDevices) {
                    val deviceName = device.name ?: "Unknown Device"
                    val deviceAddress = device.address
                    Log.d(TAG, "Adding paired device: $deviceName ($deviceAddress)")
                    discoveredDevices[deviceAddress] = Pair(deviceName, deviceAddress)
                }
                viewModelScope.launch {
                    _foundDevicesEvent.emit(discoveredDevices.values.toList())
                }
            } else {
                Log.d(TAG, "No paired devices found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error accessing paired devices", e)
        }
          if (discoveryReceiver == null) {
            discoveryReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    Log.d(TAG, "Discovery receiver triggered: ${intent} context: $context")
                    when (intent.action) {
                        BluetoothDevice.ACTION_FOUND -> {
                            // Use the newer API to avoid deprecation warning with proper null-safety
                            val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                            } else {
                                @Suppress("DEPRECATION")
                                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                            }
                            device?.let {
                                val deviceName = it.name
                                val deviceAddress = it.address
                                val bondState = getBondStateString(it.bondState)
                                val deviceClass = it.bluetoothClass?.majorDeviceClass ?: -1
                                
                                Log.d(TAG, "Discovered device: $deviceAddress")
                                Log.d(TAG, "Device details: Name='${deviceName ?: "null"}', " +
                                           "Address=$deviceAddress, " +
                                           "BondState=$bondState, " +
                                           "DeviceClass=$deviceClass" +
                                           "device=$it")
                                
                                // Some devices might not broadcast their name during discovery
                                // For those, we can try to get name if the device is already bonded
                                val finalDeviceName = deviceName ?: "Unknown Device"
                                
                                if (!discoveredDevices.containsKey(deviceAddress)) {
                                    discoveredDevices[deviceAddress] = Pair(finalDeviceName, deviceAddress)
                                    
                                    Log.d(TAG, "New device added to discovered list: $finalDeviceName ($deviceAddress)")
                                    viewModelScope.launch {
                                        _foundDevicesEvent.emit(discoveredDevices.values.toList())
                                    }
                                }
                            }
                        }
                        BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                            isDiscovering = false
                            
                            if (discoveredDevices.isEmpty()) {
                                viewModelScope.launch {
                                    _errorEvent.emit("No devices found")
                                }
                            }
                        }
                    }
                }
            }
            
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }
            
            context.registerReceiver(discoveryReceiver, filter)
        }
        
        if (bt.isDiscovering) {
            bt.cancelDiscovery()
        }
        
        bt.startDiscovery()
        Log.d(TAG, "Bluetooth discovery started")
    }

    fun connectToDevice(deviceAddress: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val bt = bluetoothAdapter
                if (bt == null) {
                    Log.e(TAG, "Bluetooth adapter not initialized")
                    _errorEvent.emit("Bluetooth adapter not initialized")
                    return@launch
                }
                
                if (bt.isDiscovering) {
                    bt.cancelDiscovery()
                }
                
                val device = bt.getRemoteDevice(deviceAddress)
                val deviceName = device.name ?: "Unknown Device"
                
                gameRepository.initializeGameAsClient()
                val success = gameRepository.connectToGame(device)
                
                if (success) {
                    _connectedToGameEvent.emit(deviceName)
                } else {
                    _errorEvent.emit("Failed to connect to $deviceName")
                }
            } catch (e: IOException) {
                _errorEvent.emit("Connection error: ${e.message}")
                Log.e(TAG, "Error connecting to device", e)
            }
        }
    }

    fun cleanup() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                discoveryReceiver?.let {
                    context.unregisterReceiver(it)
                    discoveryReceiver = null
                }
                
                bluetoothAdapter?.let { bt ->
                    if (bt.isDiscovering) {
                        bt.cancelDiscovery()
                    }
                }
                
                serverSocket?.close()
                serverSocket = null
                
                gameRepository.cleanup()
            } catch (e: Exception) {
                Log.e(TAG, "Error during cleanup", e)
            }
        }
    }
    
    private fun getBondStateString(bondState: Int): String {
        return when (bondState) {
            BluetoothDevice.BOND_BONDED -> "Bonded"
            BluetoothDevice.BOND_BONDING -> "Bonding"
            BluetoothDevice.BOND_NONE -> "Not bonded"
            else -> "Unknown bond state"
        }
    }
}
