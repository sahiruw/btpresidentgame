package com.example.bt_president_game.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.bt_president_game.R
import com.example.bt_president_game.databinding.ItemCardBinding
import com.example.bt_president_game.model.Card
import com.example.bt_president_game.model.Rank
import com.example.bt_president_game.model.Suit

class CardAdapter(
    private val selectable: Boolean,
    private val onCardClick: ((Card) -> Unit)? = null
) : ListAdapter<Card, CardAdapter.CardViewHolder>(CardDiffCallback()) {

    private var selectedCards = mutableSetOf<Card>()

    fun setSelectedCards(cards: Set<Card>) {
        selectedCards = cards.toMutableSet()
        notifyDataSetChanged() // For simplicity, we'll refresh all items
    }

    fun getSelectedCards(): Set<Card> = selectedCards

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardViewHolder {
        val binding = ItemCardBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return CardViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CardViewHolder, position: Int) {
        val card = getItem(position)
        holder.bind(card, selectedCards.contains(card), selectable)
    }

    inner class CardViewHolder(
        private val binding: ItemCardBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(card: Card, isSelected: Boolean, selectable: Boolean) {
            // Set card values
            binding.textViewCardValue.text = card.rank.symbol
            binding.textViewCardValueBottom.text = card.rank.symbol
            
            // Set suit symbols and colors
            val suitSymbol = when (card.suit) {
                Suit.HEARTS -> "♥"
                Suit.DIAMONDS -> "♦"
                Suit.CLUBS -> "♣"
                Suit.SPADES -> "♠"
            }
            
            binding.textViewCardSuit.text = suitSymbol
            binding.textViewCardSuitTop.text = suitSymbol
            
            // Set color based on suit
            val textColor = when (card.suit) {
                Suit.HEARTS, Suit.DIAMONDS -> Color.RED
                Suit.CLUBS, Suit.SPADES -> Color.BLACK
            }
            
            binding.textViewCardValue.setTextColor(textColor)
            binding.textViewCardValueBottom.setTextColor(textColor)
            binding.textViewCardSuit.setTextColor(textColor)
            binding.textViewCardSuitTop.setTextColor(textColor)
              // Handle selection
            if (selectable) {
                binding.checkBoxSelected.visibility = android.view.View.VISIBLE
                binding.checkBoxSelected.isChecked = isSelected
                  // Set the ripple background for touch feedback
                binding.cardContentLayout.setBackgroundResource(R.drawable.card_ripple_effect)
                
                if (isSelected) {
                    // Apply enhanced selection styling
                    binding.cardContentLayout.isSelected = true
                    binding.root.cardElevation = 8f  // Increase elevation for selected cards
                    binding.root.setCardBackgroundColor(ContextCompat.getColor(binding.root.context, R.color.card_selected_elevation))
                } else {
                    // Reset to default styling
                    binding.cardContentLayout.isSelected = false
                    binding.root.cardElevation = 4f  // Default elevation
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
                binding.checkBoxSelected.visibility = android.view.View.GONE
                binding.cardContentLayout.setBackgroundColor(Color.WHITE)
                binding.cardContentLayout.isSelected = false
                binding.root.cardElevation = 4f
                binding.root.setCardBackgroundColor(Color.WHITE)
                binding.root.setOnClickListener(null)
            }
        }
    }

    class CardDiffCallback : DiffUtil.ItemCallback<Card>() {
        override fun areItemsTheSame(oldItem: Card, newItem: Card): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Card, newItem: Card): Boolean {
            return oldItem == newItem
        }
    }
}
