package com.example.bt_president_game.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.bt_president_game.R
import com.example.bt_president_game.databinding.ItemPlayerBinding
import com.example.bt_president_game.model.Player
import com.example.bt_president_game.model.PlayerRank

class PlayerAdapter : ListAdapter<PlayerWithCards, PlayerAdapter.PlayerViewHolder>(PlayerDiffCallback()) {

    private var currentPlayerId: String? = null

    fun setCurrentPlayer(playerId: String?) {
        currentPlayerId = playerId
        notifyDataSetChanged() // For simplicity, notify all items
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlayerViewHolder {
        val binding = ItemPlayerBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return PlayerViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PlayerViewHolder, position: Int) {
        val playerWithCards = getItem(position)
        holder.bind(playerWithCards, currentPlayerId)
    }

    class PlayerViewHolder(
        private val binding: ItemPlayerBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(playerWithCards: PlayerWithCards, currentPlayerId: String?) {
            binding.textViewPlayerName.text = playerWithCards.player.name
            binding.textViewCardCount.text = binding.root.context.getString(
                R.string.player_cards_count,
                playerWithCards.cardCount
            )

            // Set status text based on player rank
            val statusText = when (playerWithCards.player.rank) {
                PlayerRank.PRESIDENT -> binding.root.context.getString(R.string.player_status_president)
                PlayerRank.VICE_PRESIDENT -> binding.root.context.getString(R.string.player_status_vice_president)
                PlayerRank.NEUTRAL -> ""
                PlayerRank.VICE_SCUM -> binding.root.context.getString(R.string.player_status_vice_beggar)
                PlayerRank.SCUM -> binding.root.context.getString(R.string.player_status_beggar)
            }

            // Add "Current Turn" text if this is the current player
            if (playerWithCards.player.id == currentPlayerId) {
                binding.textViewPlayerStatus.text = binding.root.context.getString(R.string.current_turn)
                binding.textViewPlayerStatus.setTextColor(
                    ContextCompat.getColor(binding.root.context, android.R.color.holo_green_dark)
                )
            } else {
                binding.textViewPlayerStatus.text = statusText
                // Set color based on rank
                val textColor = when (playerWithCards.player.rank) {
                    PlayerRank.PRESIDENT -> R.color.rank_president
                    PlayerRank.VICE_PRESIDENT -> R.color.rank_vice_president
                    PlayerRank.NEUTRAL -> android.R.color.darker_gray
                    PlayerRank.VICE_SCUM -> R.color.rank_vice_scum
                    PlayerRank.SCUM -> R.color.rank_scum
                }
                binding.textViewPlayerStatus.setTextColor(
                    ContextCompat.getColor(binding.root.context, textColor)
                )
            }
        }
    }

    class PlayerDiffCallback : DiffUtil.ItemCallback<PlayerWithCards>() {
        override fun areItemsTheSame(oldItem: PlayerWithCards, newItem: PlayerWithCards): Boolean {
            return oldItem.player.id == newItem.player.id
        }

        override fun areContentsTheSame(oldItem: PlayerWithCards, newItem: PlayerWithCards): Boolean {
            return oldItem == newItem
        }
    }
}

data class PlayerWithCards(
    val player: Player,
    val cardCount: Int
)
