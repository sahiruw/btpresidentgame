package com.example.bt_president_game.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.bt_president_game.R
import com.example.bt_president_game.adapter.CardAdapter
import com.example.bt_president_game.adapter.PlayerAdapter
import com.example.bt_president_game.adapter.PlayerWithCards
import com.example.bt_president_game.adapter.SmallCardAdapter
import com.example.bt_president_game.databinding.ActivityGameBinding
import com.example.bt_president_game.model.Card
import com.example.bt_president_game.viewmodels.GameViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class GameActivity : AppCompatActivity() {
    
    companion object {
        const val EXTRA_IS_HOST = "extra_is_host"
        const val EXTRA_HOST_DEVICE_NAME = "extra_host_device_name"
    }

    private lateinit var binding: ActivityGameBinding
    private val viewModel: GameViewModel by viewModels()

    private lateinit var playersAdapter: PlayerAdapter
    private lateinit var cardsAdapter: CardAdapter
    private lateinit var currentCardsAdapter: SmallCardAdapter

    private var isHost = false
    private var hostDeviceName = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGameBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Get intent extras
        isHost = intent.getBooleanExtra(EXTRA_IS_HOST, false)
        hostDeviceName = intent.getStringExtra(EXTRA_HOST_DEVICE_NAME) ?: ""
        
        setupViews()
        setupGame()
        observeViewModel()
    }

    private fun setupViews() {
        // Setup RecyclerViews
        binding.recyclerViewPlayers.layoutManager = LinearLayoutManager(this)
        binding.recyclerViewPlayerCards.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.recyclerViewCurrentCards.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        
        // Initialize adapters
        playersAdapter = PlayerAdapter()
        cardsAdapter = CardAdapter(true) { card -> viewModel.selectCard(card) }
        currentCardsAdapter = SmallCardAdapter()
        
        binding.recyclerViewPlayers.adapter = playersAdapter
        binding.recyclerViewPlayerCards.adapter = cardsAdapter
        binding.recyclerViewCurrentCards.adapter = currentCardsAdapter
        
        // Set up button click listeners
        binding.buttonStartGame.setOnClickListener {
            if (isHost) {
                viewModel.startGame()
            }
        }
        
        binding.buttonPlay.setOnClickListener {
            viewModel.playSelectedCards()
        }
        
        binding.buttonPass.setOnClickListener {
            viewModel.pass()
        }
        
        // Show start button only for host
        binding.buttonStartGame.visibility = if (isHost) View.VISIBLE else View.GONE
    }

    private fun setupGame() {
        // Initialize game state based on whether this device is host or client
        if (isHost) {
            binding.textViewGameStatus.text = getString(R.string.waiting_for_players)
            viewModel.initializeAsHost()
        } else {
            binding.textViewGameStatus.text = getString(R.string.connected_to_game, hostDeviceName)
            viewModel.initializeAsClient()
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.gameState.collect { gameState ->
                updateUI(gameState)
            }
        }
        
        lifecycleScope.launch {
            viewModel.errorEvent.collect { errorMessage ->
                Toast.makeText(this@GameActivity, errorMessage, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateUI(gameState: GameViewModel.GameState) {
        // Update game status
        val gameStatusText = when {
            gameState.gameEnded -> getString(R.string.game_over)
            !gameState.gameStarted -> getString(R.string.waiting_for_players)
            gameState.isMyTurn -> getString(R.string.your_turn)
            else -> getString(R.string.waiting_for_turn)
        }
        binding.textViewGameStatus.text = gameStatusText
        
        // Update player list with card counts
        val playersWithCards = gameState.players.map { player ->
            PlayerWithCards(
                player = player,
                cardCount = if (player.id == viewModel.getPlayerId()) gameState.myCards.size else 0
            )
        }
        playersAdapter.submitList(playersWithCards)
        playersAdapter.setCurrentPlayer(gameState.currentPlayerId)
        
        // Update player's cards
        cardsAdapter.submitList(gameState.myCards)
        
        // Update currently played cards
        gameState.currentPlay?.let { playedCards ->
            currentCardsAdapter.submitList(playedCards.cards)
            
            // Find player name for the played cards
            val playerName = gameState.players.find { it.id == playedCards.playerId }?.name ?: ""
            binding.textViewLastPlayer.text = getString(R.string.played_by, playerName)
            binding.cardViewCurrentPlay.visibility = View.VISIBLE
        } ?: run {
            // No cards played yet
            currentCardsAdapter.submitList(emptyList())
            binding.textViewLastPlayer.text = ""
            binding.cardViewCurrentPlay.visibility = View.GONE
        }
        
        // Update selected cards in adapter
        val selectedCards = gameState.myCards.filter { card -> viewModel.isCardSelected(card) }.toSet()
        cardsAdapter.setSelectedCards(selectedCards)
        
        // Update game controls visibility
        if (gameState.gameStarted && !gameState.gameEnded) {
            binding.layoutGameControls.visibility = View.VISIBLE
            binding.buttonStartGame.visibility = View.GONE
            
            // Enable/disable play button based on turn
            binding.buttonPlay.isEnabled = gameState.isMyTurn
            binding.buttonPass.isEnabled = gameState.isMyTurn && gameState.currentPlay != null
        } else if (gameState.gameEnded) {
            binding.layoutGameControls.visibility = View.GONE
            binding.buttonStartGame.visibility = View.GONE
            
            // Show game results
            showGameResults(gameState)
        } else {
            binding.layoutGameControls.visibility = View.GONE
            binding.buttonStartGame.visibility = if (isHost) View.VISIBLE else View.GONE
        }
    }
    
    private fun showGameResults(gameState: GameViewModel.GameState) {
        // Create result message based on player's finish order
        val playerIndex = gameState.playersFinishOrder.indexOf(viewModel.getPlayerId())
        val resultMessage = when (playerIndex) {
            0 -> getString(R.string.result_president)
            1 -> getString(R.string.result_vice_president)
            gameState.players.size - 2 -> getString(R.string.result_vice_scum)
            gameState.players.size - 1 -> getString(R.string.result_scum)
            else -> getString(R.string.result_neutral)
        }
        
        // Show dialog with game results
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.game_over)
            .setMessage(resultMessage)
            .setPositiveButton(R.string.ok) { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        viewModel.cleanup()
    }
}
