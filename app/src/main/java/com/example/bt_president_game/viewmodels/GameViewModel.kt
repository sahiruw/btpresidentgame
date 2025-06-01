package com.example.bt_president_game.viewmodels

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.bt_president_game.data.BluetoothService
import com.example.bt_president_game.data.GameRepository
import com.example.bt_president_game.model.Card
import com.example.bt_president_game.model.GameMessage
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

    private val selectedCards = mutableListOf<Card>()

    // Game state for the current device
    data class GameState(
        val isMyTurn: Boolean = false,
        val gameStarted: Boolean = false,
        val players: List<Player> = emptyList(),
        val myCards: List<Card> = emptyList(),
        val currentPlay: PlayedCards? = null,
        val currentPlayerId: String? = null,
        val gameEnded: Boolean = false,
        val playersFinishOrder: List<String> = emptyList()
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
    }

    fun selectCard(card: Card) {
        if (selectedCards.contains(card)) {
            selectedCards.remove(card)
        } else {
            // Check if the card has the same value as already selected cards
            if (selectedCards.isEmpty() || selectedCards[0].value == card.value) {
                selectedCards.add(card)
            } else {
                viewModelScope.launch {
                    _errorEvent.emit("You can only select cards of the same value")
                }
            }
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
            when (message) {
                is GameMessage.GameStarted -> {
                    _gameState.value = _gameState.value.copy(
                        gameStarted = true,
                        myCards = message.cards,
                        isMyTurn = message.firstPlayerId == gameRepository.getPlayerId()
                    )
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
                    _gameState.value = _gameState.value.copy(
                        currentPlay = message.playedCards,
                        currentPlayerId = message.playedCards.playerId
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
                        currentPlay = null,
                        currentPlayerId = null
                    )
                }
                
                else -> {
                    Log.d(TAG, "Unhandled message type: ${message::class.java.simpleName}")
                }
            }
        }
    }

    fun isCardSelected(card: Card): Boolean {
        return selectedCards.contains(card)
    }
    
    fun getPlayerId(): String {
        return gameRepository.getPlayerId()
    }

    fun cleanup() {
        viewModelScope.launch {
            gameRepository.cleanup()
        }
    }
}
