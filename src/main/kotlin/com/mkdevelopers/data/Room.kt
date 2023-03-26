package com.mkdevelopers.data

import com.mkdevelopers.data.models.*
import com.mkdevelopers.gson
import com.mkdevelopers.util.getRandomWords
import com.mkdevelopers.util.transformToUnderscores
import com.mkdevelopers.util.words
import io.ktor.websocket.*
import kotlinx.coroutines.*

class Room(
    val name: String,
    val maxPlayers: Int,
    var players: List<Player> = listOf()
) {

    private var timerJob: Job? = null
    private var drawingPlayer: Player? = null
    private var winningPlayers = listOf<String>()
    private var word: String? = null
    private var curWords: List<String>? = null
    private var drawingPlayerIndex = 0

    private var phaseChangedListener: ((Phase) -> Unit)? = null

    /**
     * multiple requests from client side to access phase,
     * it may cause concurrency issue, to avoid that used synchronized
     */
    var phase = Phase.WAITING_FOR_PLAYERS
        set(value) {
            synchronized(field) {
                field = value
                phaseChangedListener?.invoke(value)
            }
        }

    private fun setPhaseChangedListener(listener: (Phase) -> Unit) {
        phaseChangedListener = listener
    }

    init {
        /**
         * when ever phase changes the listener will trigger the appropriate functions.
         */
        setPhaseChangedListener { newPhase ->
            when(newPhase) {
                Phase.WAITING_FOR_PLAYERS -> waitingForPlayers()
                Phase.WAITING_FOR_START -> waitingForStart()
                Phase.NEW_ROUND -> newRound()
                Phase.GAME_RUNNING -> gameRunning()
                Phase.SHOW_WORD -> showWord()
            }
        }
    }

    suspend fun addPlayer(clientId: String, username: String, socketSession: WebSocketSession): Player {
        val player = Player(
            userName = username,
            socket = socketSession,
            clientId = clientId
        )
        players += player

        if(players.size == 1) {
            phase = Phase.WAITING_FOR_PLAYERS
        } else if(players.size == 2 && phase == Phase.WAITING_FOR_PLAYERS) {
            phase = Phase.WAITING_FOR_START
            players = players.shuffled()
        } else if(phase == Phase.WAITING_FOR_START && players.size == maxPlayers) {
            phase = Phase.NEW_ROUND
            players = players.shuffled()
        }

        val announcement = Announcement(
            message = "$username joined the party!",
            timestamp = System.currentTimeMillis(),
            announcementType = Announcement.TYPE_PLAYER_JOINED
        )
        broadcast(gson.toJson(announcement))

        return player
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun timeAndNotify(ms: Long) {
        timerJob?.cancel()
        timerJob = GlobalScope.launch {
            val phaseChange = PhaseChange(
                phase = phase,
                time = ms,
                drawingPlayer = drawingPlayer?.userName
            )
            repeat((ms / UPDATE_TIME_FREQUENCY).toInt()) {
                if(it != 0) {
                    phaseChange.phase = null
                }
                broadcast(gson.toJson(phaseChange))
                phaseChange.time -= UPDATE_TIME_FREQUENCY
                delay(UPDATE_TIME_FREQUENCY)
            }
            phase = when(phase) {
                Phase.WAITING_FOR_START -> Phase.NEW_ROUND
                Phase.NEW_ROUND -> Phase.GAME_RUNNING
                Phase.GAME_RUNNING -> Phase.SHOW_WORD
                Phase.SHOW_WORD -> Phase.NEW_ROUND
                else -> Phase.WAITING_FOR_PLAYERS
            }
        }
    }

    suspend fun broadcast(message: String) {
        players.forEach { player ->
            if(player.socket.isActive) {
                player.socket.send(Frame.Text(message))
            }
        }
    }

    suspend fun broadcastToAllExcept(message: String, clientId: String) {
        players.forEach { player ->
            if(player.clientId != clientId && player.socket.isActive) {
                player.socket.send(Frame.Text(message))
            }
        }
    }

    fun containsPlayer(username: String): Boolean {
        return players.find { it.userName == username } != null
    }

    fun setWordAndSwitchToGameRunning(word: String) {
        this.word = word
        phase = Phase.GAME_RUNNING
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun waitingForPlayers() {
        GlobalScope.launch {
            val phaseChange = PhaseChange(
                phase = Phase.WAITING_FOR_PLAYERS,
                time = DELAY_WAITING_FOR_START_TO_NEW_ROUND
            )
            broadcast(gson.toJson(phaseChange))
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun waitingForStart() {
        GlobalScope.launch {
            timeAndNotify(DELAY_WAITING_FOR_START_TO_NEW_ROUND)
            val phaseChange = PhaseChange(
                phase = Phase.WAITING_FOR_START,
                time = DELAY_WAITING_FOR_START_TO_NEW_ROUND
            )
            broadcast(gson.toJson(phaseChange))
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun newRound() {
        curWords = getRandomWords(3)
        val newWords = NewWords(curWords ?: listOf())
        nextDrawingPlayer()
        GlobalScope.launch {
            drawingPlayer?.socket?.send(Frame.Text(gson.toJson(newWords)))
            timeAndNotify(DELAY_NEW_ROUND_TO_GAME_RUNNING)
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun gameRunning() {
        winningPlayers = listOf()
        val wordToSend = word ?: curWords?.random() ?: words.random()
        val wordWithUnderscores = wordToSend.transformToUnderscores()
        val drawingUsername = (drawingPlayer ?: players.random()).userName

        val gameStateForDrawingPlayer = GameState(
            drawingPlayer = drawingUsername,
            word = wordToSend
        )
        val gameStateForGuessingPlayers = GameState(
            drawingPlayer = drawingUsername,
            word = wordWithUnderscores
        )

        GlobalScope.launch {
            broadcastToAllExcept(
                message = gson.toJson(gameStateForGuessingPlayers),
                clientId = drawingPlayer?.clientId ?: players.random().clientId
            )
            drawingPlayer?.socket?.send(Frame.Text(gson.toJson(gameStateForDrawingPlayer)))

            timeAndNotify(DELAY_GAME_RUNNING_TO_SHOW_WORD)

            println("Drawing phase in room $name started. It'll last ${DELAY_GAME_RUNNING_TO_SHOW_WORD / 1000}s")
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun showWord() {
        GlobalScope.launch {

            /**
             * If no winning players then 50 points will deduct from drawing player
             */
            if(winningPlayers.isEmpty()) {
                drawingPlayer?.let {
                    it.score -= PENALTY_NOBODY_GUESSED_IT
                }
            }
            word?.let {
                val chosenWord = ChosenWord(
                    chosenWord = it,
                    roomName = name
                )
                broadcast(gson.toJson(chosenWord))
            }
            timeAndNotify(DELAY_SHOW_WORD_TO_NEW_ROUND)
            val phaseChange = PhaseChange(Phase.SHOW_WORD, DELAY_SHOW_WORD_TO_NEW_ROUND)
            broadcast(gson.toJson(phaseChange))
        }
    }

    private fun nextDrawingPlayer() {
        drawingPlayer?.isDrawing = false
        if(players.isEmpty()) {
            return
        }

        drawingPlayer = if(drawingPlayerIndex <= players.size - 1){
            players[drawingPlayerIndex]
        } else {
            players.last()
        }

        if(drawingPlayerIndex < players.size - 1)
            drawingPlayerIndex++
        else
            drawingPlayerIndex = 0
    }

    //game phases
    enum class Phase {
        WAITING_FOR_PLAYERS,
        WAITING_FOR_START,
        NEW_ROUND,
        GAME_RUNNING,
        SHOW_WORD
    }

    companion object {

        const val UPDATE_TIME_FREQUENCY = 1000L

        const val DELAY_WAITING_FOR_START_TO_NEW_ROUND = 10000L
        const val DELAY_NEW_ROUND_TO_GAME_RUNNING = 20000L
        const val DELAY_GAME_RUNNING_TO_SHOW_WORD = 60000L
        const val DELAY_SHOW_WORD_TO_NEW_ROUND = 10000L

        const val PENALTY_NOBODY_GUESSED_IT = 50
    }
}