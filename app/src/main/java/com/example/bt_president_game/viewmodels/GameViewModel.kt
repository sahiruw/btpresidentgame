package com.example.bt_president_game.viewmodels

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.bt_president_game.data.BluetoothService
import com.example.bt_president_game.data.GameRepository
import com.example.bt_president_game.model.Card
import com.example.bt_president_game.model.GameMessage
import com.example.bt_president_game.model.GameState as ModelGameState
import com.example.bt_president_game.model.Player
import com.example.bt_president_game.model.PlayedCards
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import java.util.UUID

@HiltViewModel
class GameViewModel @Inject constructor(
    private val gameRepository: GameRepository
) : ViewModel() {

    companion object {
        private const val TAG = "GameViewModel"
    }

    private val _gameState = MutableStateFlow(GameState())
    val gameState: StateFlow<GameState> = _gameState    
    private val _errorEvent = MutableSharedFlow<String>()
    val errorEvent: SharedFlow<String> = _errorEvent
    
    private val _hapticFeedbackEvent = MutableSharedFlow<Unit>()
    val hapticFeedbackEvent: SharedFlow<Unit> = _hapticFeedbackEvent

    private val selectedCards = mutableListOf<Card>()    // Game state for the current device
    data class GameState(
        val isMyTurn: Boolean = false,
        val gameStarted: Boolean = false,
        val players: List<Player> = emptyList(),
        val myCards: List<Card> = emptyList(),
        val currentPlay: PlayedCards? = null,
        val currentPlayerId: String? = null,
        val gameEnded: Boolean = false,
        val playersFinishOrder: List<String> = emptyList(),
        val playerCardCounts: Map<String, Int> = emptyMap(),
        val selectedCardCount: Int = 0
    )

    fun initializeAsHost() {
        viewModelScope.launch {
            try {
                gameRepository.initializeGameAsHost()
                
                // Start listening for client connections and messages
                launch {
                    gameRepository.incomingMessages.collect { message ->
                        processIncomingMessage(message)
                    }
                }

                launch {
                    gameRepository.connectedPlayers.collect { players ->
                        _gameState.value = _gameState.value.copy(players = players)
                    }
                }
                  // Add collector for my cards
                launch {
                    gameRepository.myCards.collect { cards ->
                        Log.d(TAG, "Host received cards: ${cards.size}")
                        if (cards.isNotEmpty()) {
                            Log.d(TAG, "Cards: ${cards.joinToString { "${it.rank.symbol}${it.suit}" }}")
                        }
                        _gameState.value = _gameState.value.copy(myCards = cards)
                    }
                }
                
                // Add collector for current play
                launch {
                    gameRepository.currentPlay.collect { playedCards ->
                        _gameState.value = _gameState.value.copy(currentPlay = playedCards)
                    }
                }
                
                // Add collector for current player ID
                launch {
                    gameRepository.currentPlayerId.collect { playerId ->
                        _gameState.value = _gameState.value.copy(
                            currentPlayerId = playerId,
                            isMyTurn = playerId == gameRepository.getPlayerId()
                        )
                        Log.d(TAG, "Current player ID updated: $playerId, My ID: ${gameRepository.getPlayerId()} is my turn: ${_gameState.value.isMyTurn}")
                    }
                }
                
                // Add collector for game state
                launch {
                    gameRepository.gameState.collect { state ->
                        _gameState.value = _gameState.value.copy(
                            gameStarted = state == ModelGameState.PLAYING || state == ModelGameState.DEALING_CARDS,
                            gameEnded = state == ModelGameState.GAME_OVER
                        )
                    }
                }
                
                // Add collector for finished players
                launch {
                    gameRepository.finishedPlayers.collect { finishedPlayers ->
                        _gameState.value = _gameState.value.copy(
                            playersFinishOrder = finishedPlayers
                        )
                    }
                }

                // Add collector for player card counts
                launch {
                    gameRepository.playerCardCounts.collect { cardCounts ->
                        _gameState.value = _gameState.value.copy(
                            playerCardCounts = cardCounts
                        )
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error initializing game as host", e)
                _errorEvent.emit("Failed to initialize game: ${e.message}")
            }
        }
    }

    fun initializeAsClient() {
        viewModelScope.launch {
            try {
                gameRepository.initializeGameAsClient()
                
                // Start listening for messages from host
                launch {
                    gameRepository.incomingMessages.collect { message ->
                        processIncomingMessage(message)
                    }
                }
                
                launch {
                    gameRepository.connectedPlayers.collect { players ->
                        _gameState.value = _gameState.value.copy(players = players)
                    }
                }
                  // Add collector for my cards
                launch {
                    gameRepository.myCards.collect { cards ->
                        Log.d(TAG, "Client received cards: ${cards.size}")
                        if (cards.isNotEmpty()) {
                            Log.d(TAG, "Cards: ${cards.joinToString { "${it.rank.symbol}${it.suit}" }}")
                        }
                        _gameState.value = _gameState.value.copy(myCards = cards)
                    }
                }
                
                // Add collector for current play
                launch {
                    gameRepository.currentPlay.collect { playedCards ->
                        _gameState.value = _gameState.value.copy(currentPlay = playedCards)
                    }
                }
                
                // Add collector for current player ID
                launch {
                    gameRepository.currentPlayerId.collect { playerId ->
                        _gameState.value = _gameState.value.copy(
                            currentPlayerId = playerId,
                            isMyTurn = playerId == gameRepository.getPlayerId()
                        )
                    }
                }
                
                // Add collector for game state
                launch {
                    gameRepository.gameState.collect { state ->
                        _gameState.value = _gameState.value.copy(
                            gameStarted = state == ModelGameState.PLAYING || state == ModelGameState.DEALING_CARDS,
                            gameEnded = state == ModelGameState.GAME_OVER
                        )
                    }
                }
                
                // Add collector for finished players
                launch {
                    gameRepository.finishedPlayers.collect { finishedPlayers ->
                        _gameState.value = _gameState.value.copy(
                            playersFinishOrder = finishedPlayers
                        )
                    }
                }
                
                // Add collector for player card counts
                launch {
                    gameRepository.playerCardCounts.collect { cardCounts ->
                        _gameState.value = _gameState.value.copy(
                            playerCardCounts = cardCounts
                        )
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing game as client", e)
                _errorEvent.emit("Failed to join game: ${e.message}")
            }
        }
    }

    fun startGame() {
        if (_gameState.value.players.isEmpty()) {
            viewModelScope.launch {
                _errorEvent.emit("Cannot start game with no players")
            }
            return
        }

        viewModelScope.launch {
            try {
                gameRepository.startGame()
                _gameState.value = _gameState.value.copy(gameStarted = true)
            } catch (e: Exception) {
                Log.e(TAG, "Error starting game", e)
                _errorEvent.emit("Failed to start game: ${e.message}")
            }
        }
    }    fun selectCard(card: Card) {
        Log.d(TAG, "Card selected: ${card.rank.symbol}${card.suit}")
        
        if (selectedCards.contains(card)) {
            selectedCards.remove(card)
            Log.d(TAG, "Card removed from selection. Selected count: ${selectedCards.size}")
        } else {
            // Check if the card has the same value as already selected cards
            if (selectedCards.isEmpty() || selectedCards[0].value == card.value) {
                selectedCards.add(card)
                Log.d(TAG, "Card added to selection. Selected count: ${selectedCards.size}")
            } else {
                // If selecting a different value, clear previous selection and add this one
                selectedCards.clear()
                selectedCards.add(card)
                Log.d(TAG, "Previous selection cleared, new card selected")
            }
        }
        
        // Apply haptic feedback to indicate selection change
        viewModelScope.launch {
            _hapticFeedbackEvent.emit(Unit)
        }
        
        // Notify UI of selection change
        viewModelScope.launch {
            _gameState.value = _gameState.value.copy(selectedCardCount = selectedCards.size)  // Trigger UI update
        }
    }

    fun playSelectedCards() {
        if (selectedCards.isEmpty()) {
            viewModelScope.launch {
                _errorEvent.emit("Select cards to play first")
            }
            return
        }

        val currentPlay = _gameState.value.currentPlay
        
        // Check if play is valid
        if (currentPlay != null && 
            (selectedCards.size != currentPlay.cards.size || 
             selectedCards[0].value <= currentPlay.cards[0].value)) {
            viewModelScope.launch {
                _errorEvent.emit("Invalid play: you must play the same number of cards with a higher value")
            }
            return
        }

        viewModelScope.launch {
            try {
                val playedCards = PlayedCards(selectedCards.toList(), gameRepository.getPlayerId())
                
                // Update local state
                val updatedCards = _gameState.value.myCards.filter { card -> 
                    !selectedCards.contains(card) 
                }
                
                _gameState.value = _gameState.value.copy(
                    myCards = updatedCards,
                    currentPlay = playedCards,
                    currentPlayerId = gameRepository.getPlayerId(),
                    isMyTurn = false
                )
                
                // Send the move to other players                
                gameRepository.playCards(playedCards)
                
                selectedCards.clear()
                viewModelScope.launch {
                    _gameState.value = _gameState.value.copy(selectedCardCount = 0)
                }
                
                // Check if player has finished
                if (updatedCards.isEmpty()) {
                    gameRepository.playerFinished()
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error playing cards", e)
                _errorEvent.emit("Failed to play cards: ${e.message}")
            }
        }
    }

    fun pass() {
        if (!_gameState.value.isMyTurn) {
            viewModelScope.launch {
                _errorEvent.emit("It's not your turn")
            }
            return
        }

        viewModelScope.launch {
            try {
                gameRepository.pass()
                _gameState.value = _gameState.value.copy(isMyTurn = false)
            } catch (e: Exception) {
                Log.e(TAG, "Error passing turn", e)
                _errorEvent.emit("Failed to pass: ${e.message}")
            }
        }
    }

    private fun processIncomingMessage(message: GameMessage) {
        viewModelScope.launch {
            when (message) {                is GameMessage.GameStarted -> {
                    // Only update the turn information here since myCards will be updated via flow
                    _gameState.value = _gameState.value.copy(
                        gameStarted = true,
                        isMyTurn = message.firstPlayerId == gameRepository.getPlayerId()
                    )
                    Log.d(TAG, "Game started message received. First player: ${message.firstPlayerId}, My ID: ${gameRepository.getPlayerId()}")
                }
                
                is GameMessage.UpdatePlayers -> {
                    _gameState.value = _gameState.value.copy(
                        players = message.players
                    )
                }
                
                is GameMessage.UpdateTurn -> {
                    _gameState.value = _gameState.value.copy(
                        isMyTurn = message.playerId == gameRepository.getPlayerId()
                    )
                }
                  is GameMessage.CardsPlayed -> {
                    // Update current play and card counts
                    val updatedCardCounts = _gameState.value.playerCardCounts.toMutableMap()
                    updatedCardCounts[message.playedCards.playerId] = message.remainingCardCount
                    
                    _gameState.value = _gameState.value.copy(
                        currentPlay = message.playedCards,
                        currentPlayerId = message.playedCards.playerId,
                        playerCardCounts = updatedCardCounts
                    )
                }
                
                is GameMessage.PlayerPassed -> {
                    // Just log this, as the next UpdateTurn will handle the turn transition
                    Log.d(TAG, "Player ${message.playerId} passed")
                }
                
                is GameMessage.GameEnded -> {
                    _gameState.value = _gameState.value.copy(
                        gameEnded = true,
                        playersFinishOrder = message.playerRanking
                    )
                }
                  is GameMessage.ResetTable -> {
                    _gameState.value = _gameState.value.copy(
                        currentPlay = null
                    )
                    Log.d(TAG, "Table reset received - new round starting")
                }
                
                else -> {
                    Log.d(TAG, "Unhandled message type: ${message::class.java.simpleName}")
                }
            }
        }
    }

    fun getPlayerId(): String {
        return gameRepository.getPlayerId()
    }
    
    fun isCardSelected(card: Card): Boolean {
        return selectedCards.contains(card)
    }

    fun hasSelectedCards(): Boolean {
        Log.d(TAG, "Checking if any cards are selected: ${selectedCards} selected")
        return selectedCards.isNotEmpty()
    }
    
    fun getSelectedCardCount(): Int {
        return selectedCards.size
    }

    fun cleanup() {
        viewModelScope.launch {
            gameRepository.cleanup()
        }
    }
}
