package com.example.bt_president_game.model
import java.io.Serializable

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class Card(
    val id: Int,
    val suit: Suit,
    val rank: Rank
) : Parcelable,Serializable, Comparable<Card> {
    val value: Int
        get() = rank.value
        
    override fun compareTo(other: Card): Int {
        return rank.value.compareTo(other.rank.value)
    }
}

enum class Suit {
    CLUBS,
    DIAMONDS,
    HEARTS,
    SPADES
}

enum class Rank(val value: Int, val symbol: String) : Serializable {
    THREE(3, "3"),
    FOUR(4, "4"),
    FIVE(5, "5"),
    SIX(6, "6"),
    SEVEN(7, "7"),
    EIGHT(8, "8"),
    NINE(9, "9"),
    TEN(10, "10"),
    JACK(11, "J"),
    QUEEN(12, "Q"),
    KING(13, "K"),
    ACE(14, "A"),
    TWO(15, "2")
}

