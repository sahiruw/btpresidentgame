package com.example.bt_president_game.viewmodels

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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

    private lateinit var bluetoothAdapter: BluetoothAdapter
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
        bluetoothAdapter = adapter
        gameRepository.initializeBluetooth(adapter)
    }

    fun startHostingGame() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                gameRepository.initializeGameAsHost()
                
                // Start accepting connections in the background
                val success = gameRepository.startHostingGame()
                if (success) {
                    _gameCreatedEvent.emit(Unit)
                } else {
                    _errorEvent.emit("Failed to start hosting game")
                }
            } catch (e: IOException) {
                _errorEvent.emit("Failed to create game: ${e.message}")
                Log.e(TAG, "Error starting server socket", e)
            }
        }
    }

    fun startDiscovery() {
        if (isDiscovering) return
        
        isDiscovering = true
        discoveredDevices.clear()
        
        if (discoveryReceiver == null) {
            discoveryReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    when (intent.action) {
                        BluetoothDevice.ACTION_FOUND -> {
                            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                            device?.let {
                                val deviceName = it.name ?: "Unknown Device"
                                val deviceAddress = it.address
                                
                                if (!discoveredDevices.containsKey(deviceAddress)) {
                                    discoveredDevices[deviceAddress] = Pair(deviceName, deviceAddress)
                                    
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
        
        if (bluetoothAdapter.isDiscovering) {
            bluetoothAdapter.cancelDiscovery()
        }
        
        bluetoothAdapter.startDiscovery()
    }

    fun connectToDevice(deviceAddress: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (bluetoothAdapter.isDiscovering) {
                    bluetoothAdapter.cancelDiscovery()
                }
                
                val device = bluetoothAdapter.getRemoteDevice(deviceAddress)
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
                
                if (bluetoothAdapter.isDiscovering) {
                    bluetoothAdapter.cancelDiscovery()
                }
                
                serverSocket?.close()
                serverSocket = null
                
                gameRepository.cleanup()
            } catch (e: Exception) {
                Log.e(TAG, "Error during cleanup", e)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        cleanup()
    }
}
