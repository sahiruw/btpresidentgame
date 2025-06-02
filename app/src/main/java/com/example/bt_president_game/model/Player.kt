package com.example.bt_president_game.model
import java.io.Serializable

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class Player(
    val address: String,
    val id: String,
    val name: String,
    val isHost: Boolean = false,
    val rank: PlayerRank = PlayerRank.NEUTRAL
) : Parcelable, Serializable

enum class PlayerRank {
    PRESIDENT,
    VICE_PRESIDENT,
    NEUTRAL,
    VICE_SCUM,
    SCUM
}
