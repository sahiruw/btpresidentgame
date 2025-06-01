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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GameRepository @Inject constructor() {
    
    companion object {
        private const val TAG = "GameRepository"
        private const val MAX_PLAYERS = 8 // Maximum number of players in a president game
        private val SERVICE_UUID = UUID.fromString("fa87c0d0-afac-11de-8a39-0800200c9a66")
    }
    
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
    
    // Message flow for incoming game messages
    private val _incomingMessages = MutableSharedFlow<GameMessage>()
    val incomingMessages: SharedFlow<GameMessage> = _incomingMessages

    fun initializeGameAsHost() {
        if (bluetoothAdapter == null) {
            throw IllegalStateException("BluetoothAdapter not initialized")
        }

        _isHost.value = true
        _gameState.value = GameState.WAITING_FOR_PLAYERS
        
        // Create a server socket and listen for connections
        serverSocket = bluetoothAdapter?.listenUsingRfcommWithServiceRecord("PresidentGame", SERVICE_UUID)
        
        // Initialize the BluetoothService if not already done
        if (bluetoothService == null) {
            bluetoothService = BluetoothService(bluetoothAdapter!!, ::handleRawMessage)
        }
        
        // Add self as the first player (host)
        val hostName = bluetoothAdapter?.name ?: "Host"
        val hostPlayer = Player(id = playerId, name = hostName, isHost = true)
        _connectedPlayers.value = listOf(hostPlayer)
    }

    fun initializeGameAsClient() {
        if (bluetoothAdapter == null) {
            throw IllegalStateException("BluetoothAdapter not initialized")
        }
        
        _isHost.value = false
        _gameState.value = GameState.CONNECTING
        
        // Initialize the BluetoothService if not already done
        if (bluetoothService == null) {
            bluetoothService = BluetoothService(bluetoothAdapter!!, ::handleRawMessage)
        }
        
        // Add self as a player
        val playerName = bluetoothAdapter?.name ?: "Player"
        val player = Player(id = playerId, name = playerName, isHost = false)
        _connectedPlayers.value = listOf(player)
    }

    suspend fun startHostingGame(): Boolean {
        serverSocket?.let { socket ->
            return bluetoothService?.startAcceptingConnections(socket, MAX_PLAYERS - 1) ?: false
        }
        return false
    }

    suspend fun connectToGame(device: BluetoothDevice): Boolean {
        _gameState.value = GameState.CONNECTING
        val connected = bluetoothService?.connectToServer(device, SERVICE_UUID) ?: false
        
        if (connected) {
            _gameState.value = GameState.WAITING_FOR_PLAYERS
            
            // Request the current game state from the host
            val message = serializeMessage(GameMessage.RequestGameState)
            bluetoothService?.sendMessageToAll(message)
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
        for ((id, cards) in playerCards) {
            if (cards.any { card -> card.suit == Suit.CLUBS && card.rank == Rank.THREE }) {
                firstPlayerId = id
                break
            }
        }
        
        // Send start game message to all players
        for (player in players) {
            if (player.id != playerId) { // Don't send to self
                val cards = playerCards[player.id] ?: emptyList()
                val startMessage = GameMessage.GameStarted(cards, firstPlayerId)
                val serializedMessage = serializeMessage(startMessage)
                bluetoothService?.sendMessage(serializedMessage, player.id)
            }
        }
        
        // Update game state
        _gameState.value = GameState.PLAYING
        _currentPlayerId.value = firstPlayerId
        
        // Create game state update for all players
        val gameStateMessage = GameMessage.GameState(
            currentState = GameState.PLAYING,
            players = _connectedPlayers.value,
            currentPlay = null,
            currentPlayerId = firstPlayerId,
            nextPlayerId = null,
            finishedPlayers = emptyList()
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
        
        // Send cards played message to all players
        val message = GameMessage.CardsPlayed(playedCards)
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
        // Logic to check if a round is over (everyone else has passed)
        // If so, reset the table and let the last player who played start again
        // This is simplified for now
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
            val gameMessage = deserializeMessage(rawMessage)
            if (gameMessage != null) {
                processGameMessage(gameMessage, senderId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing message", e)
        }
    }
    
    private fun processGameMessage(message: GameMessage, senderId: String) {
        when (message) {
            is GameMessage.PlayerJoined -> {
                val newPlayer = message.player
                
                // Add the new player to connected players
                val currentPlayers = _connectedPlayers.value.toMutableList()
                if (!currentPlayers.any { it.id == newPlayer.id }) {
                    currentPlayers.add(newPlayer)
                    _connectedPlayers.value = currentPlayers
                }
            }
            
            is GameMessage.GameStarted -> {
                _gameState.value = GameState.PLAYING
                _myCards.value = message.cards
                _currentPlayerId.value = message.firstPlayerId
            }
            
            is GameMessage.CardsPlayed -> {
                _currentPlay.value = message.playedCards
            }
            
            is GameMessage.PlayerPassed -> {
                // Nothing to do here, next player determination is done on host side
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
                        finishedPlayers = _finishedPlayers.value
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
        try {
            val byteArrayOutputStream = ByteArrayOutputStream()
            val objectOutputStream = ObjectOutputStream(byteArrayOutputStream)
            objectOutputStream.writeObject(message)
            objectOutputStream.flush()
            
            // Convert to Base64 string for safe transmission
            return android.util.Base64.encodeToString(
                byteArrayOutputStream.toByteArray(),
                android.util.Base64.DEFAULT
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error serializing message", e)
            return ""
        }
    }
    
    private fun deserializeMessage(serializedMessage: String): GameMessage? {
        try {
            val bytes = android.util.Base64.decode(serializedMessage, android.util.Base64.DEFAULT)
            val byteArrayInputStream = ByteArrayInputStream(bytes)
            val objectInputStream = ObjectInputStream(byteArrayInputStream)
            return objectInputStream.readObject() as GameMessage
        } catch (e: Exception) {
            Log.e(TAG, "Error deserializing message", e)
            return null
        }
    }
    
    fun getPlayerId(): String {
        return playerId
    }    fun cleanup() {
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
    }
}
