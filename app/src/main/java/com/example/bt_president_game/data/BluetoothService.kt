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
        private const val MESSAGE_DELIMITER = "##END_OF_MESSAGE##" // Special marker for end of complete message
        private const val BUFFER_TIMEOUT_MS = 5000 // 5 seconds timeout for message buffer
    }    
    
    // Store incomplete messages by device ID
    private val messageBuffers = ConcurrentHashMap<String, MessageBuffer>()
    private val connectedSockets = ConcurrentHashMap<String, ConnectedDevice>()
    private var isRunning = false
    
    // Class to handle message buffering and detect complete messages
    private inner class MessageBuffer {
        private val buffer = StringBuilder()
        private var lastUpdateTime = System.currentTimeMillis()
        
        // Add fragment to buffer and return complete message if available
        @Synchronized
        fun appendFragment(fragment: String): List<String>? {
            lastUpdateTime = System.currentTimeMillis()
            buffer.append(fragment)

            if (buffer.contains(MESSAGE_DELIMITER)) {
                val messages = buffer.toString().split(MESSAGE_DELIMITER)
                
                // The last element might be an incomplete message fragment, keep it in the buffer
                buffer.clear()
                buffer.append(messages.last())

                // Return all complete messages (without the delimiter)
                return messages.dropLast(1).filter { it.isNotEmpty() }
            }

            return null
        }

        
        // Check if buffer is stale and should be processed anyway
        @Synchronized
        fun isStale(): Boolean {
            return System.currentTimeMillis() - lastUpdateTime > BUFFER_TIMEOUT_MS
        }
        
        // Get and clear buffer contents if stale
        @Synchronized
        fun getAndClearIfStale(): String? {
            if (isStale() && buffer.isNotEmpty()) {
                val content = buffer.toString()
                buffer.clear()
                return content
            }
            return null
        }
        
        @Synchronized
        fun clear() {
            buffer.clear()
        }
    }
    
    // Callback for when at least one player has connected
    private var onFirstPlayerConnected: (() -> Unit)? = null
    
    // Set the callback for first player connection
    fun setOnFirstPlayerConnectedCallback(callback: () -> Unit) {
        onFirstPlayerConnected = callback
    }
    
    suspend fun startAcceptingConnections(serverSocket: BluetoothServerSocket, maxConnections: Int): Boolean {
        isRunning = true
        
        return withContext(Dispatchers.IO) {
            try {
                var connectionCount = 0
                var retryCount = 0
                val maxRetries = 3
                var firstConnectionNotified = false
                
                while (isRunning && connectionCount < maxConnections) {
                    try {                        
                        Log.d(TAG, "Waiting for incoming connections... (Attempt ${retryCount + 1})")
                        // This call will block until a connection is accepted or an exception occurs
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
                        
                        // Notify that we have at least one connection - but only once
                        if (!firstConnectionNotified && connectionCount > 0) {
                            firstConnectionNotified = true
                            withContext(Dispatchers.Main) {
                                onFirstPlayerConnected?.invoke()
                            }
                        }
                        
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
                
                // Return true as long as we have at least one connection, even if we didn't reach maxConnections
                val result = connectionCount > 0 || !isRunning // Success if we have connections or if we stopped voluntarily
                Log.d(TAG, "Connection acceptance completed with result: $result (connections: $connectionCount)")
                result
            } catch (e: Exception) {
                Log.e(TAG, "Error in acceptThread", e)
                false
            }
        }
    }suspend fun connectToServer(device: BluetoothDevice, uuid: UUID): Boolean {
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
        Log.d(TAG, "Sending message to $recipientId Socket: ${connectedSockets[recipientId]}")
        connectedSockets[recipientId]?.write(message)
    }

    fun sendMessageToAll(message: String) {
        connectedSockets.values.forEach { device ->
            device.write(message)
        }
    }    fun stop() {
        isRunning = false
        
        // Close all connected sockets
        connectedSockets.values.forEach { device ->
            device.close()
        }
        connectedSockets.clear()
        
        // Clear message buffers
        messageBuffers.clear()
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
            
            // Create or get message buffer for this device
            val messageBuffer = messageBuffers.getOrPut(deviceId) { MessageBuffer() }
            
            Thread {
                val buffer = ByteArray(1024)
                var bytes: Int
                
                while (isRunning) {
                    try {
                        // Read from the inputStream
                        bytes = inputStream?.read(buffer) ?: -1
                        
                        if (bytes > 0) {
                            // Convert to string
                            val fragment = String(buffer, 0, bytes)
                            Log.d(TAG, "Received fragment of ${fragment.length} bytes from $deviceId $fragment")
                            
                            // Add to buffer and check if we have a complete message
                            val completeMessages = messageBuffer.appendFragment(fragment)
                            
                            
                            if (completeMessages != null && completeMessages.isNotEmpty()) {
                                // We have one or more complete messages
                                for (completeMessage in completeMessages) {
                                    Log.d(TAG, "Assembled complete message of ${completeMessage.length} bytes")
                                    messageHandler(completeMessage, deviceId)
                                }
                            } else {
                                                            // Check for stale messages that should be processed anyway
                                val staleMessage = messageBuffer.getAndClearIfStale()
                                if (staleMessage != null) {
                                    Log.d(TAG, "Processing stale message of ${staleMessage.length} bytes (timeout)")
                                    messageHandler(staleMessage, deviceId)
                                }
                            }
                        }
                    } catch (e: IOException) {
                        Log.e(TAG, "Connection lost", e)
                        isRunning = false
                        connectedSockets.remove(deviceId)
                        messageBuffers.remove(deviceId) // Clean up the message buffer
                        break
                    }
                }
            }.start()
        }        
        
        fun write(message: String) {
            try {
                // Add delimiter to mark the end of the complete message
                val messageWithDelimiter = message + MESSAGE_DELIMITER
                Log.d(TAG, "Sending message of ${messageWithDelimiter.length} bytes to $deviceId $messageWithDelimiter")
                outputStream?.write(messageWithDelimiter.toByteArray(Charsets.UTF_8))

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
