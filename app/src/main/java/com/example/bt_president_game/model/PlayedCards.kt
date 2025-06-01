package com.example.bt_president_game.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.io.Serializable

@Parcelize
data class PlayedCards(
    val cards: List<Card>,
    val playerId: String
) : Parcelable, Serializable
