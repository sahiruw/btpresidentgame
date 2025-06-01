package com.example.bt_president_game.data

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.util.Log
import kotlinx.coroutines.Dispatchers
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
    }

    private val connectedSockets = ConcurrentHashMap<String, ConnectedDevice>()
    private var isRunning = false

    suspend fun startAcceptingConnections(serverSocket: BluetoothServerSocket, maxConnections: Int): Boolean {
        isRunning = true
        
        return withContext(Dispatchers.IO) {
            try {
                var connectionCount = 0
                
                while (isRunning && connectionCount < maxConnections) {
                    try {
                        // This call will block until a connection is accepted or an exception occurs
                        val socket = serverSocket.accept()
                        
                        val deviceId = socket.remoteDevice.address
                        val connectedDevice = ConnectedDevice(socket, deviceId)
                        connectedSockets[deviceId] = connectedDevice
                        
                        // Start a thread to handle communication with this device
                        connectedDevice.startCommunication()
                        connectionCount++
                        
                        // Send current players list to the new client
                        // sendPlayersList(deviceId)
                        
                    } catch (e: IOException) {
                        Log.e(TAG, "Accept failed", e)
                        break
                    }
                }
                
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error in acceptThread", e)
                false
            }
        }
    }

    suspend fun connectToServer(device: BluetoothDevice, uuid: UUID): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Get a BluetoothSocket for a connection with the given BluetoothDevice
                val socket = device.createRfcommSocketToServiceRecord(uuid)
                
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
