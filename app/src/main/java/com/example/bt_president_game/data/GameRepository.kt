package com.example.bt_president_game.data

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.util.Log
import com.example.bt_president_game.model.GameState
import com.example.bt_president_game.model.Player
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GameRepository @Inject constructor() {
    
    companion object {
        private const val TAG = "GameRepository"
        private const val MAX_PLAYERS = 8 // Maximum number of players in a president game
    }
    
    private var bluetoothService: BluetoothService? = null
    private val _gameState = MutableStateFlow<GameState?>(null)
    val gameState: StateFlow<GameState?> = _gameState
    
    private val _connectedPlayers = MutableStateFlow<List<Player>>(emptyList())
    val connectedPlayers: StateFlow<List<Player>> = _connectedPlayers
    
    private val _isHost = MutableStateFlow(false)
    val isHost: StateFlow<Boolean> = _isHost

    fun initializeBluetoothService(bluetoothAdapter: BluetoothAdapter) {
        bluetoothService = BluetoothService(bluetoothAdapter, ::handleReceivedMessage)
    }

    suspend fun startHostingGame(serverSocket: BluetoothServerSocket): Boolean {
        bluetoothService?.let { service ->
            _isHost.value = true
            _gameState.value = GameState.WAITING_FOR_PLAYERS
            
            // Clear any existing players and add self as first player
            val hostPlayer = Player(id = "host", name = "Host", isHost = true)
            _connectedPlayers.value = listOf(hostPlayer)
            
            return service.startAcceptingConnections(serverSocket, MAX_PLAYERS - 1) // -1 to account for host
        }
        
        return false
    }

    suspend fun connectToGame(device: BluetoothDevice, uuid: UUID): Boolean {
        bluetoothService?.let { service ->
            _isHost.value = false
            _gameState.value = GameState.CONNECTING
            
            val connected = service.connectToServer(device, uuid)
            if (connected) {
                _gameState.value = GameState.WAITING_FOR_PLAYERS
                return true
            }
        }
        
        return false
    }
    
    fun startGame() {
        if (_isHost.value) {
            // Only the host can start the game
            _gameState.value = GameState.DEALING_CARDS
            
            // Send start game command to all clients
            bluetoothService?.sendMessageToAll("START_GAME")
            
            // Simulate dealing cards
            dealCards()
        }
    }
    
    private fun dealCards() {
        // In a real implementation, this would shuffle and deal cards to all players
        // For now, we'll just set the game state to playing after a short delay
        _gameState.value = GameState.PLAYING
    }

    fun playCard(cardId: Int) {
        bluetoothService?.sendMessageToAll("PLAY_CARD:$cardId")
        // Update local game state
    }
    
    fun passMove() {
        bluetoothService?.sendMessageToAll("PASS")
        // Update local game state
    }

    private fun handleReceivedMessage(message: String, senderSocketId: String) {
        when {
            message.startsWith("JOIN:") -> {
                val playerName = message.substringAfter("JOIN:")
                val newPlayer = Player(id = senderSocketId, name = playerName, isHost = false)
                
                // Add the new player to connected players
                val currentPlayers = _connectedPlayers.value.toMutableList()
                currentPlayers.add(newPlayer)
                _connectedPlayers.value = currentPlayers
                
                // Let others know about the new player
                if (_isHost.value) {
                    bluetoothService?.sendMessageToAll("PLAYER_JOINED:${newPlayer.id},${newPlayer.name}")
                }
            }
            
            message == "START_GAME" -> {
                _gameState.value = GameState.DEALING_CARDS
                // Wait for cards to be dealt
            }
            
            message.startsWith("DEALT_CARDS:") -> {
                // Process dealt cards
                _gameState.value = GameState.PLAYING
            }
            
            message.startsWith("PLAY_CARD:") -> {
                val cardId = message.substringAfter("PLAY_CARD:").toIntOrNull()
                // Process played card
            }
            
            message == "PASS" -> {
                // Handle player passing their turn
            }
            
            message.startsWith("GAME_OVER:") -> {
                val winnerId = message.substringAfter("GAME_OVER:")
                _gameState.value = GameState.GAME_OVER
                // Show winner information
            }
        }
    }

    fun cleanup() {
        bluetoothService?.stop()
        bluetoothService = null
        _gameState.value = null
        _connectedPlayers.value = emptyList()
        _isHost.value = false
    }
}
