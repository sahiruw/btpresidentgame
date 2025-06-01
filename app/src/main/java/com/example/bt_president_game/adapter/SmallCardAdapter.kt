package com.example.bt_president_game.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.bt_president_game.R
import com.example.bt_president_game.databinding.ItemCardSmallBinding
import com.example.bt_president_game.model.Card
import com.example.bt_president_game.model.Rank
import com.example.bt_president_game.model.Suit

class SmallCardAdapter : ListAdapter<Card, SmallCardAdapter.SmallCardViewHolder>(SmallCardDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SmallCardViewHolder {
        val binding = ItemCardSmallBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return SmallCardViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SmallCardViewHolder, position: Int) {
        val card = getItem(position)
        holder.bind(card)
    }

    class SmallCardViewHolder(
        private val binding: ItemCardSmallBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(card: Card) {
            // Set card values
            binding.textViewCardValue.text = card.rank.symbol
            
            // Set suit symbols and colors
            val suitSymbol = when (card.suit) {
                Suit.HEARTS -> "♥"
                Suit.DIAMONDS -> "♦"
                Suit.CLUBS -> "♣"
                Suit.SPADES -> "♠"
            }
            
            binding.textViewCardSuit.text = suitSymbol
            binding.textViewCardSuitCenter.text = suitSymbol
            
            // Set color based on suit
            val textColor = when (card.suit) {
                Suit.HEARTS, Suit.DIAMONDS -> Color.RED
                Suit.CLUBS, Suit.SPADES -> Color.BLACK
            }
            
            binding.textViewCardValue.setTextColor(textColor)
            binding.textViewCardSuit.setTextColor(textColor)
            binding.textViewCardSuitCenter.setTextColor(textColor)
        }
    }

    class SmallCardDiffCallback : DiffUtil.ItemCallback<Card>() {
        override fun areItemsTheSame(oldItem: Card, newItem: Card): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Card, newItem: Card): Boolean {
            return oldItem == newItem
        }
    }
}
