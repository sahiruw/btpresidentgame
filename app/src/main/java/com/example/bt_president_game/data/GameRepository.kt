package com.example.bt_president_game.data

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.util.Log
import com.example.bt_president_game.model.Card
import com.example.bt_president_game.model.GameMessage
import com.example.bt_president_game.model.GameState
import com.example.bt_president_game.model.Player
import com.example.bt_president_game.model.PlayedCards
import com.example.bt_president_game.model.Rank
import com.example.bt_president_game.model.Suit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.UUID
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GameRepository @Inject constructor() {
    
    companion object {
        private const val TAG = "GameRepository"
        private const val MAX_PLAYERS = 2 // Maximum number of players in a president game
        private val SERVICE_UUID = UUID.fromString("fa87c0d0-afac-11de-8a39-0800200c9a66")
    }
    
    // Create a coroutine scope for this repository
    private val viewModelScope = CoroutineScope(Dispatchers.Main)
    
    // Device ID for the local player (using a random UUID for now)
    private val playerId = UUID.randomUUID().toString()
    
    // Bluetooth-related variables
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothService: BluetoothService? = null
    private var serverSocket: BluetoothServerSocket? = null
      // Game state related flows
    private val _gameState = MutableStateFlow<GameState>(GameState.WAITING_FOR_PLAYERS)
    val gameState: StateFlow<GameState> = _gameState
    
    private val _connectedPlayers = MutableStateFlow<List<Player>>(emptyList())
    val connectedPlayers: StateFlow<List<Player>> = _connectedPlayers
    
    private val _isHost = MutableStateFlow(false)
    val isHost: StateFlow<Boolean> = _isHost
    
    private val _myCards = MutableStateFlow<List<Card>>(emptyList())
    val myCards: StateFlow<List<Card>> = _myCards
    
    private val _currentPlay = MutableStateFlow<PlayedCards?>(null)
    val currentPlay: StateFlow<PlayedCards?> = _currentPlay
    
    private val _currentPlayerId = MutableStateFlow<String?>(null)
    val currentPlayerId: StateFlow<String?> = _currentPlayerId
    
    private val _finishedPlayers = MutableStateFlow<List<String>>(emptyList())
    val finishedPlayers: StateFlow<List<String>> = _finishedPlayers
    
    // Track card counts for all players
    private val _playerCardCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val playerCardCounts: StateFlow<Map<String, Int>> = _playerCardCounts
    
    // Message flow for incoming game messages
    private val _incomingMessages = MutableSharedFlow<GameMessage>()
    val incomingMessages: SharedFlow<GameMessage> = _incomingMessages

    fun initializeGameAsHost() {
        Log.d(TAG, "Initializing game as host")
        
        if (bluetoothAdapter == null) {
            Log.e(TAG, "BluetoothAdapter not initialized. Attempting to reinitialize.")
            throw IllegalStateException("BluetoothAdapter not initialized")
        }
        
        _isHost.value = true
        _gameState.value = GameState.WAITING_FOR_PLAYERS
        
        // Create a server socket and listen for connections
        // Using insecureRfcommWithServiceRecord makes it easier to connect without pairing
        serverSocket = bluetoothAdapter?.listenUsingInsecureRfcommWithServiceRecord("PresidentGame", SERVICE_UUID)
        if (serverSocket == null) {
            Log.e(TAG, "Failed to create server socket")
            throw IOException("Could not create server socket")
        }
        Log.d(TAG, "Server socket created, waiting for connections...")
        
        // Initialize the BluetoothService if not already done
        if (bluetoothService == null) {
            Log.d(TAG, "Creating BluetoothService instance for host")
            bluetoothAdapter?.let {
                bluetoothService = BluetoothService(it, ::handleRawMessage)
            } ?: run {
                Log.e(TAG, "Cannot create BluetoothService - adapter is null")
                throw IllegalStateException("BluetoothAdapter is null")
            }
        }
        
        // Add self as the first player (host)
        val hostName = bluetoothAdapter?.name ?: "Host"
        val hostPlayer = Player(id = playerId, name = hostName, isHost = true, address = bluetoothAdapter?.address ?: "unknown")
        
        if (_connectedPlayers.value.isEmpty()) {
            _connectedPlayers.value = listOf(hostPlayer)
            Log.d(TAG, "Added host player: ${hostPlayer.name} (${hostPlayer.id})")
        } else {
            Log.w(TAG, "Connected players list already has players, not adding host again")
        }
    }

    fun initializeGameAsClient() {
        Log.d(TAG, "Initializing game as client")
        
        if (bluetoothAdapter == null) {
            Log.e(TAG, "BluetoothAdapter not initialized. Cannot initialize game as client.")
            throw IllegalStateException("BluetoothAdapter not initialized")
        }
        
        _isHost.value = false
        _gameState.value = GameState.CONNECTING
        
        // Initialize the BluetoothService if not already done
        if (bluetoothService == null) {
            Log.d(TAG, "Creating BluetoothService instance for client")
            bluetoothAdapter?.let {
                bluetoothService = BluetoothService(it, ::handleRawMessage)
            } ?: run {
                Log.e(TAG, "Cannot create BluetoothService - adapter is null")
                throw IllegalStateException("BluetoothAdapter is null")
            }
        }
        
        // Add self as a player
        val playerName = bluetoothAdapter?.name ?: "Player"
        val player = Player(id = playerId, name = playerName, isHost = false, address = bluetoothAdapter?.address ?: "unknown")
        _connectedPlayers.value = listOf(player)
    }    // Add flow to emit when players connect
    private val _playersConnectedEvent = MutableSharedFlow<Boolean>()
    val playersConnectedEvent: SharedFlow<Boolean> = _playersConnectedEvent
    
    suspend fun startHostingGame(): Boolean {
        if (bluetoothAdapter == null) {
            Log.e(TAG, "Cannot start hosting game - BluetoothAdapter is null")
            return false
        }
        
        if (bluetoothService == null) {
            Log.e(TAG, "Cannot start hosting game - BluetoothService is null")
            // Try to initialize it again
            bluetoothAdapter?.let {
                bluetoothService = BluetoothService(it, ::handleRawMessage)
            } ?: run {
                return false
            }
        }
        
        // Set up callback for when first player connects
        bluetoothService?.setOnFirstPlayerConnectedCallback {
            Log.d(TAG, "At least one player connected, ready to start game")
            viewModelScope.launch {
                _playersConnectedEvent.emit(true)
            }
        }
        
        serverSocket?.let { socket ->
            Log.d(TAG, "Starting to accept connections on server socket")
            return bluetoothService?.startAcceptingConnections(socket, MAX_PLAYERS - 1) ?: false
        }
        
        Log.e(TAG, "Cannot start hosting game - serverSocket is null")
        return false
    }

    suspend fun connectToGame(device: BluetoothDevice): Boolean {
        if (bluetoothAdapter == null) {
            Log.e(TAG, "Cannot connect to game - BluetoothAdapter is null")
            return false
        }
        
        if (bluetoothService == null) {
            Log.e(TAG, "Cannot connect to game - BluetoothService is null")
            // Try to initialize it again
            bluetoothAdapter?.let {
                bluetoothService = BluetoothService(it, ::handleRawMessage)
            } ?: run {
                return false
            }
        }
        
        _gameState.value = GameState.CONNECTING
        
        Log.d(TAG, "Connecting to game hosted by ${device.name} (${device.address})")
        val connected = bluetoothService?.connectToServer(device, SERVICE_UUID) ?: false
        
        if (connected) {
            _gameState.value = GameState.WAITING_FOR_PLAYERS

            // Send PlayerJoined message to the host
            val player = Player(id = playerId, name = bluetoothAdapter?.name ?: "Player", isHost = false, address = device.address)
            val joinMessage = GameMessage.PlayerJoined(player)
            val serializedMessage = serializeMessage(joinMessage)
            bluetoothService?.sendMessage(serializedMessage, device.address)
            // bluetoothService?.sendMessageToAll(serializedMessage)
            Log.d(TAG, "Successfully connected to game hosted by ${device.name} (${device.address})")
            
            // Request the current game state from the host
            val message = serializeMessage(GameMessage.RequestGameState)
            bluetoothService?.sendMessageToAll(message)
        }
        else {
            Log.e(TAG, "Failed to connect to game hosted by ${device.name} (${device.address})")
            _gameState.value = GameState.WAITING_FOR_PLAYERS
        }
        
        return connected
    }
    
    fun startGame() {
        if (!_isHost.value) {
            throw IllegalStateException("Only the host can start the game")
        }

        // Change game state to dealing cards
        _gameState.value = GameState.DEALING_CARDS
        
        // Create a full deck of cards
        val deck = createFullDeck()
        
        // Shuffle the deck
        deck.shuffle()
        
        // Determine number of cards per player
        val players = _connectedPlayers.value
        val cardsPerPlayer = deck.size / players.size
        
        // Distribute cards to all players
        val playerCards = mutableMapOf<String, List<Card>>()
        for (i in players.indices) {
            val start = i * cardsPerPlayer
            val end = if (i == players.size - 1) deck.size else (i + 1) * cardsPerPlayer
            val cards = deck.subList(start, end)
            playerCards[players[i].id] = cards
            
            // If this is the host's cards, set my cards
            if (players[i].id == playerId) {
                _myCards.value = cards
            }
        }
        
        // Determine who goes first (player with 3 of clubs)
        var firstPlayerId = players.first().id
        // for ((id, cards) in playerCards) {
        //     if (cards.any { card -> card.suit == Suit.CLUBS && card.rank == Rank.THREE }) {
        //         firstPlayerId = id
        //         break
        //     }
        // }
        
        // Send start game message to all players
        for (player in players) {
            Log.d(TAG, "Sending start game message to player: ${player.name} (${player.id})")
            if (player.id != playerId) { // Don't send to self
                val originalCards = playerCards[player.id] ?: emptyList()
                val cards = ArrayList(originalCards) // Ensure it's a serializable full copy

                val startMessage = GameMessage.GameStarted(cards, firstPlayerId)
                val serializedMessage = serializeMessage(startMessage)
                bluetoothService?.sendMessage(serializedMessage, player.address)
            }
            else {
                _myCards.value = playerCards[player.id] ?: emptyList()
            }
        }
        
        // Update game state
        _gameState.value = GameState.PLAYING
        _currentPlayerId.value = firstPlayerId
          // Initialize card counts for all players
        val initialCardCounts = playerCards.mapValues { (_, cards) -> cards.size }
        _playerCardCounts.value = initialCardCounts
        
        // Create game state update for all players
        val gameStateMessage = GameMessage.GameState(
            currentState = GameState.PLAYING,
            players = _connectedPlayers.value,
            currentPlay = null,
            currentPlayerId = firstPlayerId,
            nextPlayerId = null,
            finishedPlayers = emptyList(),
            playerCardCounts = initialCardCounts
        )
        
        // Send game state update to all players
        val serializedStateMessage = serializeMessage(gameStateMessage)
        bluetoothService?.sendMessageToAll(serializedStateMessage)
    }
    
    private fun createFullDeck(): MutableList<Card> {
        val deck = mutableListOf<Card>()
        var cardId = 0
        
        for (suit in Suit.values()) {
            for (rank in Rank.values()) {
                deck.add(Card(cardId++, suit, rank))
            }
        }
        
        return deck
    }    
    
    fun playCards(playedCards: PlayedCards) {
        // Remove played cards from my hand
        val currentCards = _myCards.value.toMutableList()
        currentCards.removeAll(playedCards.cards)
        _myCards.value = currentCards
        
        // Update current play
        _currentPlay.value = playedCards
        
        // Update my card count in the tracking map
        val currentCardCounts = _playerCardCounts.value.toMutableMap()
        currentCardCounts[playerId] = currentCards.size
        _playerCardCounts.value = currentCardCounts
        
        // Send cards played message to all players
        val message = GameMessage.CardsPlayed(playedCards, currentCards.size)
        val serializedMessage = serializeMessage(message)
        bluetoothService?.sendMessageToAll(serializedMessage)
        
        // Determine next player
        determineNextPlayer()
    }
    
    fun pass() {
        // Send pass message to all players
        val message = GameMessage.PlayerPassed(playerId)
        val serializedMessage = serializeMessage(message)
        bluetoothService?.sendMessageToAll(serializedMessage)
        
        // Determine next player
        determineNextPlayer()
    }

    private fun determineNextPlayer() {
        val players = _connectedPlayers.value
        val currentPlayerIndex = players.indexOfFirst { it.id == _currentPlayerId.value }
        
        if (currentPlayerIndex == -1) return
        
        // Find the next player who hasn't finished yet
        var nextIndex = (currentPlayerIndex + 1) % players.size
        var loopCount = 0
        
        while (loopCount < players.size) {
            val nextPlayerId = players[nextIndex].id
            
            if (!_finishedPlayers.value.contains(nextPlayerId)) {
                // Found the next player
                _currentPlayerId.value = nextPlayerId
                
                // Send update turn message
                val message = GameMessage.UpdateTurn(nextPlayerId)
                val serializedMessage = serializeMessage(message)
                bluetoothService?.sendMessageToAll(serializedMessage)
                
                // Check if everyone except this player has passed
                checkForRoundEnd()
                return
            }
            
            nextIndex = (nextIndex + 1) % players.size
            loopCount++
        }
        
        // If we get here, all players have finished
        endGame()
    }
      private fun checkForRoundEnd() {
        // Check if everyone has passed except the current player
        val players = _connectedPlayers.value
        val activePlayerCount = players.size - _finishedPlayers.value.size
        
        if (activePlayerCount <= 1) {
            // Only one player left, they win this round
            return
        }
        
        // Get the player who most recently played cards
        val lastPlayerToPlay = _currentPlay.value?.playerId
        
        if (lastPlayerToPlay != null && lastPlayerToPlay == _currentPlayerId.value) {
            // The current player is the one who played the last cards
            // and turn has come back to them - everyone else has passed
            
            // Reset the table
            _currentPlay.value = null
            
            // Send reset table message
            val resetMessage = GameMessage.ResetTable
            val serializedMessage = serializeMessage(resetMessage)
            bluetoothService?.sendMessageToAll(serializedMessage)
            
            Log.d(TAG, "Round ended, table reset. Current player gets another turn: $lastPlayerToPlay")
        }
    }
    
    fun playerFinished() {
        // Add player to finished list
        val updatedFinishedPlayers = _finishedPlayers.value.toMutableList()
        updatedFinishedPlayers.add(playerId)
        _finishedPlayers.value = updatedFinishedPlayers
        
        // Check if only one player is left
        val activePlayers = _connectedPlayers.value.filter { player ->
            !_finishedPlayers.value.contains(player.id)
        }
        
        if (activePlayers.size <= 1) {
            // Game is over
            endGame()
        } else {
            // Send player finished message
            val message = serializeMessage(GameMessage.PlayerPassed(playerId))
            bluetoothService?.sendMessageToAll(message)
            
            // Determine next player
            determineNextPlayer()
        }
    }
    
    private fun endGame() {
        // Set game state to GAME_OVER
        _gameState.value = GameState.GAME_OVER
        
        // Send game ended message with player rankings
        val message = GameMessage.GameEnded(_finishedPlayers.value)
        val serializedMessage = serializeMessage(message)
        bluetoothService?.sendMessageToAll(serializedMessage)
    }
      
    
    private fun handleRawMessage(rawMessage: String, senderId: String) {
        try {
            Log.d(TAG, "Received complete message of ${rawMessage.length} bytes from $senderId")
            
            // Try to deserialize and process
            val gameMessage = deserializeMessage(rawMessage)
            if (gameMessage != null) {
                Log.d(TAG, "Successfully deserialized message type: ${gameMessage.javaClass.simpleName}")
                processGameMessage(gameMessage, senderId)
            } else {
                Log.e(TAG, "Failed to deserialize message - null result")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing message: ${e.message}", e)
        }
    }
    
    private fun processGameMessage(message: GameMessage, senderId: String) {
        Log.d(TAG, "Processing message of type: ${message.javaClass.simpleName} from $senderId")
        Log.d(TAG, "Message content: $message")

        when (message) {
            is GameMessage.PlayerJoined -> {
                val originalPlayer = message.player
                val newPlayer = originalPlayer.copy(address = senderId) // Create a new Player with updated id
                Log.d(TAG, "Player joined: ${newPlayer.name} (${newPlayer.id})")
                
                // Add the new player to connected players
                val currentPlayers = _connectedPlayers.value.toMutableList()
                if (!currentPlayers.any { it.id == newPlayer.id }) {
                    currentPlayers.add(newPlayer)
                    _connectedPlayers.value = currentPlayers
                    Log.d(TAG, "Updated connected players: ${_connectedPlayers.value.map { it.name }}")
                }
            }
            
            is GameMessage.GameStarted -> {
                Log.d(TAG, "Game started with cards: ${message}")
                _gameState.value = GameState.PLAYING
                _myCards.value = message.cards
                _currentPlayerId.value = message.firstPlayerId
            }
            
            is GameMessage.CardsPlayed -> {                _currentPlay.value = message.playedCards
                
                // Update card count for the player who played cards
                val currentCardCounts = _playerCardCounts.value.toMutableMap()
                currentCardCounts[message.playedCards.playerId] = message.remainingCardCount
                _playerCardCounts.value = currentCardCounts
            }
              is GameMessage.PlayerPassed -> {
                // For tracking purposes, add the player who passed to a temporary tracking set
                Log.d(TAG, "Player ${message.playerId} passed their turn")
            }
            
            is GameMessage.UpdateTurn -> {
                _currentPlayerId.value = message.playerId
            }
            
            is GameMessage.GameEnded -> {
                _gameState.value = GameState.GAME_OVER
                _finishedPlayers.value = message.playerRanking
            }
            
            is GameMessage.ResetTable -> {
                _currentPlay.value = null
            }
              
            is GameMessage.RequestGameState -> {
                if (_isHost.value) {
                    // Send current game state to the requesting player                    
                    val currentState = GameMessage.GameState(
                        currentState = _gameState.value,
                        players = _connectedPlayers.value,
                        currentPlay = _currentPlay.value,
                        currentPlayerId = _currentPlayerId.value,
                        nextPlayerId = null, // Not used in this context
                        finishedPlayers = _finishedPlayers.value,
                        playerCardCounts = _playerCardCounts.value
                    )
                    
                    val serializedMessage = serializeMessage(currentState)
                    bluetoothService?.sendMessage(serializedMessage, senderId)
                }
            }
              
            is GameMessage.GameState -> {
                // Update local game state based on received state
                _gameState.value = message.currentState
                _connectedPlayers.value = message.players
                _currentPlay.value = message.currentPlay
                _currentPlayerId.value = message.currentPlayerId
                _finishedPlayers.value = message.finishedPlayers
                _playerCardCounts.value = message.playerCardCounts
            }
            
            is GameMessage.UpdateCardCounts -> {
                _playerCardCounts.value = message.cardCounts
            }
            
            is GameMessage.PlayerLeft -> {
                val updatedPlayers = _connectedPlayers.value.filter { it.id != message.playerId }
                _connectedPlayers.value = updatedPlayers
            }
            
            is GameMessage.UpdatePlayers -> {
                _connectedPlayers.value = message.players
            }
        }
    }    
    
    // Serialization and deserialization methods
    private fun serializeMessage(message: GameMessage): String {
        Log.d(TAG, "Serializing message: ${message.javaClass.simpleName}")
        try {
            val byteArrayOutputStream = ByteArrayOutputStream()
            val objectOutputStream = ObjectOutputStream(byteArrayOutputStream)
            objectOutputStream.writeObject(message)
            objectOutputStream.flush()
            
            val rawBytes = byteArrayOutputStream.toByteArray()
            Log.d(TAG, "Serialized message size: ${rawBytes.size} bytes")
            
            // Convert to Base64 string for safe transmission
            val base64String = android.util.Base64.encodeToString(
                rawBytes,
                android.util.Base64.NO_WRAP // Use NO_WRAP to avoid newlines in the encoded string
            )
            
            Log.d(TAG, "Base64 encoded message size: ${base64String.length} characters")
            return base64String
        } catch (e: Exception) {
            Log.e(TAG, "Error serializing message", e)
            return ""
        }
    }
    
    private fun deserializeMessage(serializedMessage: String): GameMessage? {
        Log.d(TAG, "Deserializing message of length: ${serializedMessage.length}")
        
        try {
            // First, validate the Base64 string
            if (serializedMessage.isEmpty()) {
                Log.e(TAG, "Empty message received")
                return null
            }
            
            // Decode the Base64 string to bytes
            val bytes = android.util.Base64.decode(serializedMessage, android.util.Base64.NO_WRAP)
            Log.d(TAG, "Decoded byte array length: ${bytes.size}")
            Log.d(TAG, "Decoded byte array content: ${bytes.joinToString(", ") { it.toString() }}")
            
            // Create input streams
            val byteArrayInputStream = ByteArrayInputStream(bytes)
            val objectInputStream = ObjectInputStream(byteArrayInputStream)
            
            // Read and cast the object
            val result = objectInputStream.readObject() as? GameMessage
            
            if (result == null) {
                Log.e(TAG, "Deserialized object is not a GameMessage")
            } else {
                Log.d(TAG, "Successfully deserialized to ${result.javaClass.simpleName}")
            }
            
            return result
        } catch (e: Exception) {
            Log.e(TAG, "Error deserializing message: ${e.javaClass.simpleName}: ${e.message}")
            return null
        }
    }
    
    fun getPlayerId(): String {
        return playerId
    }    
    
    fun cleanup() {
        bluetoothService?.stop()
        bluetoothService = null
        
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing server socket", e)
        }
        
        serverSocket = null
        _gameState.value = GameState.WAITING_FOR_PLAYERS
        _connectedPlayers.value = emptyList()
        _isHost.value = false
        _myCards.value = emptyList()
        _currentPlay.value = null
        _currentPlayerId.value = null
        _finishedPlayers.value = emptyList()
    }    
    
    fun initializeBluetooth(adapter: BluetoothAdapter) {
        this.bluetoothAdapter = adapter
        
        // Log Bluetooth adapter details
        Log.d(TAG, "Initializing Bluetooth adapter:")
        Log.d(TAG, "- Name: ${adapter.name}")
        Log.d(TAG, "- Address: ${adapter.address}")
        Log.d(TAG, "- State: ${getAdapterStateString(adapter.state)}")
        Log.d(TAG, "- Scanning: ${adapter.isDiscovering}")
        Log.d(TAG, "- Enabled: ${adapter.isEnabled}")
        Log.d(TAG, "- Discovery allowed: ${adapter.scanMode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE}")
        
        // Initialize BluetoothService right away to avoid null issues
        if (bluetoothService == null) {
            Log.d(TAG, "Creating BluetoothService instance")
            bluetoothService = BluetoothService(adapter, ::handleRawMessage)
        } else {
            Log.d(TAG, "BluetoothService already initialized")
        }
        
        // Get paired devices and log them
        try {
            val pairedDevices = adapter.bondedDevices
            if (pairedDevices.isNotEmpty()) {
                Log.d(TAG, "Paired devices (${pairedDevices.size}):")
                pairedDevices.forEach { device ->
                    Log.d(TAG, "  - ${device.name ?: "Unknown"} (${device.address})")
                }
            } else {
                Log.d(TAG, "No paired devices found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error accessing paired devices: ${e.message}")
        }
        
        // Register a BluetoothManagerCallback to prevent "getBluetoothService() called with no BluetoothManagerCallback" warning
        try {
            // Get BluetoothManager's class
            val managerClass = Class.forName("android.bluetooth.BluetoothManager")
            
            // Get the registerAdapter method
            val method = managerClass.getDeclaredMethod("registerAdapter", BluetoothAdapter::class.java)
            method.isAccessible = true
            
            // Get the BluetoothManager instance from adapter
            val field = BluetoothAdapter::class.java.getDeclaredField("mManagerCallback")
            field.isAccessible = true
            val managerCallback = field.get(adapter)
            
            if (managerCallback != null) {
                Log.d(TAG, "BluetoothManagerCallback already registered")
            } else {
                Log.d(TAG, "Initializing Bluetooth adapter with proper callbacks")
            }
        } catch (e: Exception) {
            // This is a workaround for the warning, so we just log the error if it doesn't work
            Log.d(TAG, "Could not access BluetoothManager internals: ${e.message}")
        }
    }
    
    // Helper function to convert adapter state to string
    private fun getAdapterStateString(state: Int): String {
        return when (state) {
            BluetoothAdapter.STATE_OFF -> "OFF"
            BluetoothAdapter.STATE_TURNING_ON -> "TURNING_ON"
            BluetoothAdapter.STATE_ON -> "ON"
            BluetoothAdapter.STATE_TURNING_OFF -> "TURNING_OFF"
            else -> "UNKNOWN"
        }
    }
}
