package com.example.bt_president_game.model

import java.io.Serializable

sealed class GameMessage : Serializable {
    // Message sent when a player joins the game
    data class PlayerJoined(val player: Player) : GameMessage()
    
    // Message sent when a player leaves the game
    data class PlayerLeft(val playerId: String) : GameMessage()
    
    // Message sent when the game starts
    data class GameStarted(val cards: List<Card>, val firstPlayerId: String) : GameMessage()
    
    // Message sent to update the player list
    data class UpdatePlayers(val players: List<Player>) : GameMessage()
    
    // Message sent when a player's turn changes
    data class UpdateTurn(val playerId: String) : GameMessage()
    
    // Message sent when cards are played
    data class CardsPlayed(val playedCards: PlayedCards, val remainingCardCount: Int) : GameMessage()
    
    // Message sent when a player passes their turn
    data class PlayerPassed(val playerId: String) : GameMessage()

    // Message sent when a player finishes their turn
    data class PlayerFinished(val playerId: String) : GameMessage()
    
    // Message sent when the game ends
    data class GameEnded(val playerRanking: List<String>) : GameMessage()
    
    // Message sent to reset the table (e.g., when everyone passes)
    object ResetTable : GameMessage()
    
    // Message to request the game state (sent by players who join mid-game)
    object RequestGameState : GameMessage()
    
    // Message sent to update all players about current card counts
    data class UpdateCardCounts(val cardCounts: Map<String, Int>) : GameMessage()
    
    // Message to communicate game state to new players
    data class GameState(
        val currentState: com.example.bt_president_game.model.GameState,
        val players: List<Player>,
        val currentPlay: PlayedCards?,
        val currentPlayerId: String?,
        val nextPlayerId: String?,
        val finishedPlayers: List<String>,
        val playerCardCounts: Map<String, Int>
    ) : GameMessage()
}
