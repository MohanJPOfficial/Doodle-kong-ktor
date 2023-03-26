package com.mkdevelopers.data

import com.mkdevelopers.data.models.Announcement
import com.mkdevelopers.data.models.PhaseChange
import com.mkdevelopers.gson
import io.ktor.websocket.*
import kotlinx.coroutines.*

class Room(
    val name: String,
    val maxPlayers: Int,
    var players: List<Player> = listOf()
) {

    private var timerJob: Job? = null
    private var drawingPlayer: Player? = null

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

    private fun newRound() {

    }

    private fun gameRunning() {

    }

    private fun showWord() {

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
    }
}