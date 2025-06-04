package com.example.bt_president_game.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.bt_president_game.R
import com.example.bt_president_game.databinding.ItemCardSmallBinding
import com.example.bt_president_game.model.Card
import com.example.bt_president_game.model.Rank
import com.example.bt_president_game.model.Suit

class SmallCardAdapter(
    private val selectable: Boolean = false,
    private val onCardClick: ((Card) -> Unit)? = null
) : ListAdapter<Card, SmallCardAdapter.SmallCardViewHolder>(SmallCardDiffCallback()) {

    private var selectedCards = mutableSetOf<Card>()

    fun setSelectedCards(cards: Set<Card>) {
        selectedCards = cards.toMutableSet()
        notifyDataSetChanged() // For simplicity, we'll refresh all items
    }

    fun getSelectedCards(): Set<Card> = selectedCards

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
        holder.bind(card, selectedCards.contains(card), selectable, onCardClick)
    }    class SmallCardViewHolder(
        private val binding: ItemCardSmallBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(card: Card, isSelected: Boolean, selectable: Boolean, onCardClick: ((Card) -> Unit)?) {
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
            
            // Handle selection
            if (selectable) {                // Set the ripple background for touch feedback
                binding.cardContentLayout.setBackgroundResource(R.drawable.card_ripple_effect)
                
                if (isSelected) {
                    // Apply enhanced selection styling
                    binding.cardContentLayout.isSelected = true
                    binding.root.cardElevation = 6f  // Increase elevation for selected cards
                    binding.root.setCardBackgroundColor(ContextCompat.getColor(binding.root.context, R.color.card_selected_elevation))
                } else {
                    // Reset to default styling
                    binding.cardContentLayout.isSelected = false
                    binding.root.cardElevation = 2f  // Default elevation
                    binding.root.setCardBackgroundColor(Color.WHITE)
                }
                
                // Add a scale animation when clicked
                binding.root.setOnClickListener {
                    it.animate()
                        .scaleX(0.95f)
                        .scaleY(0.95f)
                        .setDuration(100)
                        .withEndAction {
                            it.animate()
                                .scaleX(1f)
                                .scaleY(1f)
                                .setDuration(100)
                                .start()
                            onCardClick?.invoke(card)
                        }
                        .start()
                }
            } else {
                binding.cardContentLayout.setBackgroundColor(Color.WHITE)
                binding.cardContentLayout.isSelected = false
                binding.root.cardElevation = 2f
                binding.root.setCardBackgroundColor(Color.WHITE)
                binding.root.setOnClickListener(null)
            }
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
