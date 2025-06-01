package com.example.bt_president_game.data

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class BluetoothService(
    private val bluetoothAdapter: BluetoothAdapter,
    private val messageHandler: (String, String) -> Unit
) {

    companion object {
        private const val TAG = "BluetoothService"
    }    private val connectedSockets = ConcurrentHashMap<String, ConnectedDevice>()
    private var isRunning = false
    
    suspend fun startAcceptingConnections(serverSocket: BluetoothServerSocket, maxConnections: Int): Boolean {
        isRunning = true
        
        return withContext(Dispatchers.IO) {
            try {
                var connectionCount = 0
                var retryCount = 0
                val maxRetries = 3
                
                while (isRunning && connectionCount < maxConnections) {
                    try {                        
                        Log.d(TAG, "Waiting for incoming connections... (Attempt ${retryCount + 1})")                        // This call will block until a connection is accepted or an exception occurs
                        // For better compatibility, use the non-timeout version of accept
                        Log.d(TAG, "Server is discoverable: ${bluetoothAdapter.scanMode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE}")
                        val socket = serverSocket.accept()
                        
                        // Reset retry counter on successful connection
                        retryCount = 0
                        Log.d(TAG, "Connection accepted from ${socket} (${socket.remoteDevice})")
                        val deviceId = socket.remoteDevice.address
                        val deviceName = socket.remoteDevice.name ?: "Unknown"
                        Log.d(TAG, "Device connected: $deviceName ($deviceId)")
                        
                        val connectedDevice = ConnectedDevice(socket, deviceId)
                        connectedSockets[deviceId] = connectedDevice
                        
                        // Start a thread to handle communication with this device
                        connectedDevice.startCommunication()
                        connectionCount++
                        
                        Log.d(TAG, "Accepted connection from $deviceId, total connections: $connectionCount")
                        // Send current players list to the new client
                        // sendPlayersList(deviceId)
                        
                    } catch (e: IOException) {
                        Log.e(TAG, "Accept failed", e)
                        retryCount++
                        
                        // Only break if we've exceeded max retries or if it's not a "Try again" error
                        if (retryCount >= maxRetries || e.message?.contains("Try again") != true) {
                            Log.e(TAG, "Max retries exceeded or fatal error occurred, giving up: ${e.message}")
                            break
                        }
                          Log.d(TAG, "Retrying after transient error (${e.message}), attempt $retryCount of $maxRetries")
                        delay(2000) // Wait 2 seconds before retrying
                    }
                }
                
                val result = connectionCount > 0 || !isRunning // Success if we have connections or if we stopped voluntarily
                Log.d(TAG, "Connection acceptance completed with result: $result (connections: $connectionCount)")
                result
            } catch (e: Exception) {
                Log.e(TAG, "Error in acceptThread", e)
                false
            }
        }
    }    suspend fun connectToServer(device: BluetoothDevice, uuid: UUID): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Connecting to server device: ${device.name} (${device.address})")
                Log.d(TAG, "Device class: ${device.bluetoothClass?.majorDeviceClass}, bond state: ${device.bondState}")
                
                // Get a BluetoothSocket for a connection with the given BluetoothDevice
                // Using createInsecureRfcommSocketToServiceRecord for better compatibility
                val socket = device.createInsecureRfcommSocketToServiceRecord(uuid)
                
                // Try to connect to the remote device
                socket.connect()
                
                val deviceId = device.address
                val connectedDevice = ConnectedDevice(socket, deviceId)
                connectedSockets[deviceId] = connectedDevice
                
                // Start thread to handle communication
                connectedDevice.startCommunication()
                
                // Send join message with player name
                val playerName = bluetoothAdapter.name ?: "Player"
                sendMessage("JOIN:$playerName", deviceId)
                
                true
            } catch (e: IOException) {
                Log.e(TAG, "Connection to server failed", e)
                false
            }
        }
    }

    fun sendMessage(message: String, recipientId: String) {
        connectedSockets[recipientId]?.write(message)
    }

    fun sendMessageToAll(message: String) {
        connectedSockets.values.forEach { device ->
            device.write(message)
        }
    }

    fun stop() {
        isRunning = false
        
        // Close all connected sockets
        connectedSockets.values.forEach { device ->
            device.close()
        }
        connectedSockets.clear()
    }

    private inner class ConnectedDevice(
        private val socket: BluetoothSocket,
        val deviceId: String
    ) {
        private var inputStream: InputStream? = null
        private var outputStream: OutputStream? = null
        private var isRunning = false

        init {
            try {
                inputStream = socket.inputStream
                outputStream = socket.outputStream
            } catch (e: IOException) {
                Log.e(TAG, "Error getting socket streams", e)
            }
        }

        fun startCommunication() {
            isRunning = true
            
            Thread {
                val buffer = ByteArray(1024)
                var bytes: Int
                
                while (isRunning) {
                    try {
                        // Read from the inputStream
                        bytes = inputStream?.read(buffer) ?: -1
                        
                        if (bytes > 0) {
                            // Convert to string and process
                            val receivedMessage = String(buffer, 0, bytes)
                            messageHandler(receivedMessage, deviceId)
                        }
                    } catch (e: IOException) {
                        Log.e(TAG, "Connection lost", e)
                        isRunning = false
                        connectedSockets.remove(deviceId)
                        break
                    }
                }
            }.start()
        }

        fun write(message: String) {
            try {
                outputStream?.write(message.toByteArray())
            } catch (e: IOException) {
                Log.e(TAG, "Error sending data", e)
            }
        }

        fun close() {
            isRunning = false
            
            try {
                inputStream?.close()
                outputStream?.close()
                socket.close()
            } catch (e: IOException) {
                Log.e(TAG, "Error closing socket", e)
            }
        }
    }
}
