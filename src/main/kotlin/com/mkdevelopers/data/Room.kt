package com.mkdevelopers.data

import com.mkdevelopers.data.models.*
import com.mkdevelopers.gson
import com.mkdevelopers.server
import com.mkdevelopers.util.getRandomWords
import com.mkdevelopers.util.matchesWord
import com.mkdevelopers.util.transformToUnderscores
import com.mkdevelopers.util.words
import io.ktor.websocket.*
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

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
    private var startTime = 0L

    private val playerRemoveJobs = ConcurrentHashMap<String, Job>()
    private val leftPlayers = ConcurrentHashMap<String, Pair<Player, Int>>()

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

    /**
     * adding a new player or
     * keeping left players alive at specific index if rejoins
     */
    suspend fun addPlayer(clientId: String, username: String, socketSession: WebSocketSession): Player {
        var indexToAdd = players.size - 1
        val player = if(leftPlayers.containsKey(clientId)) {
            val leftPlayer = leftPlayers[clientId]
            leftPlayer?.first?.let { player ->
                player.socket = socketSession
                player.isDrawing = drawingPlayer?.clientId == clientId
                indexToAdd = leftPlayer.second

                playerRemoveJobs[clientId]?.cancel()
                playerRemoveJobs.remove(clientId)
                leftPlayers.remove(clientId)
                player

            } ?: Player(
                userName = username,
                socket = socketSession,
                clientId = clientId
            )
        } else {
            Player(
                userName = username,
                socket = socketSession,
                clientId = clientId
            )
        }

        indexToAdd = when {
            players.isEmpty() -> 0
            indexToAdd >= players.size -> players.size - 1
            else -> indexToAdd
        }

        val tmpPlayers = players.toMutableList()
        tmpPlayers.add(indexToAdd, player)
        players = tmpPlayers.toList()

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
        sendWordToPlayer(player)
        broadcastPlayerStates()
        broadcast(gson.toJson(announcement))

        return player
    }

    /**
     * removing player logic
     */
    @OptIn(DelicateCoroutinesApi::class)
    fun removePlayer(clientId: String) {
        val player = players.find { it.clientId == clientId} ?: return
        val index = players.indexOf(player)
        leftPlayers[clientId] = player to index
        players = players - player

        playerRemoveJobs[clientId] = GlobalScope.launch {
            delay(PLAYER_REMOVE_TIME)

            val playerToRemove = leftPlayers[clientId]
            leftPlayers.remove(clientId)
            playerToRemove?.let {
                players = players - it.first
            }
            playerRemoveJobs.remove(clientId)
        }
        val announcement = Announcement(
            message = "${player.userName} left the party :(",
            timestamp = System.currentTimeMillis(),
            announcementType = Announcement.TYPE_PLAYER_LEFT
        )

        GlobalScope.launch {
            broadcastPlayerStates()
            broadcast(gson.toJson(announcement))

            if(players.size == 1) {
                phase = Phase.WAITING_FOR_PLAYERS
                timerJob?.cancel()
            } else if(players.isEmpty()) {
                kill()
                server.rooms.remove(name)
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun timeAndNotify(ms: Long) {
        timerJob?.cancel()
        timerJob = GlobalScope.launch {
            startTime = System.currentTimeMillis()
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

    private fun isGuessCorrect(guess: ChatMessage): Boolean {
        return guess.matchesWord(word ?: return false) && !winningPlayers.contains(guess.from) &&
                guess.from != drawingPlayer?.userName && phase == Phase.GAME_RUNNING
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
            broadcastPlayerStates()
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
            broadcastPlayerStates()
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

    /**
     * Add player to winning players list
     * also checks winners size is max or not
     * if yes then it will return a new round phase
     * else returns false
     */
    private fun addWinningPlayer(username: String): Boolean {
        winningPlayers = winningPlayers + username

        if(winningPlayers.size == players.size - 1) {
            phase = Phase.NEW_ROUND
            return true
        }
        return false
    }

    /**
     * check if word is correct or not and returns true or false
     * if correct - provide scores and broadcast message to player
     * also checks if rounds is over then broadcast message to players
     */
    suspend fun checkWordAndNotifyPlayers(message: ChatMessage): Boolean {
        if(isGuessCorrect(message)) {
            val guessingTime = System.currentTimeMillis() - startTime
            val timePercentageLeft = 1f - guessingTime.toFloat() / DELAY_GAME_RUNNING_TO_SHOW_WORD
            val score = GUESS_SCORE_DEFAULT + GUESS_SCORE_PERCENTAGE_MULTIPLIER * timePercentageLeft
            val player = players.find { it.userName == message.from }

            player?.let {
                it.score += score.toInt()
            }
            drawingPlayer?.let {
                it.score += GUESS_SCORE_FOR_DRAWING_PLAYER / players.size
            }
            broadcastPlayerStates()

            val announcement = Announcement(
                message = "${message.from} has guessed it!",
                timestamp = System.currentTimeMillis(),
                announcementType = Announcement.TYPE_PLAYER_GUESSED_WORD
            )
            broadcast(gson.toJson(announcement))

            val isRoundOver = addWinningPlayer(message.from)
            if(isRoundOver) {
                val roundOverAnnouncement = Announcement(
                    message = "Everybody guessed it! New round is starting...",
                    timestamp = System.currentTimeMillis(),
                    announcementType = Announcement.TYPE_EVERYBODY_GUESSED_IT
                )
                broadcast(gson.toJson(roundOverAnnouncement))
            }
            return true
        }
        return false
    }

    /**
     * Used for sorting players by score
     * assigning rank to players
     * broadcast to client
     */
    private suspend fun broadcastPlayerStates() {
        val playersList = players.sortedByDescending { it.score }.map { player ->
            PlayerData(
                username = player.userName,
                isDrawing = player.isDrawing,
                score = player.score,
                rank = player.rank
            )
        }

        playersList.forEachIndexed { index, playerData ->
            playerData.rank = index + 1
        }
        broadcast(gson.toJson(PlayersList(playersList)))
    }

    /**
     * This function called whenever player joins room or
     * player reconnects to room
     */
    private suspend fun sendWordToPlayer(player: Player) {
         val delay = when(phase) {
             Phase.WAITING_FOR_START -> DELAY_WAITING_FOR_START_TO_NEW_ROUND
             Phase.NEW_ROUND -> DELAY_NEW_ROUND_TO_GAME_RUNNING
             Phase.GAME_RUNNING -> DELAY_GAME_RUNNING_TO_SHOW_WORD
             Phase.SHOW_WORD -> DELAY_SHOW_WORD_TO_NEW_ROUND
             else -> 0L
         }
        val phaseChange = PhaseChange(
            phase = phase,
            time = delay,
            drawingPlayer = drawingPlayer?.userName
        )

        word?.let { curWord ->
            drawingPlayer?.let { drawingPlayer ->
                val gameState = GameState(
                    drawingPlayer = drawingPlayer.userName,
                    word = if(player.isDrawing || phase == Phase.SHOW_WORD) {
                        curWord
                    } else {
                        curWord.transformToUnderscores()
                    }
                )
                player.socket.send(Frame.Text(gson.toJson(gameState)))
            }
        }
        player.socket.send(Frame.Text(gson.toJson(phaseChange)))
    }

    //choosing next draw player by next index to the drawing player
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

    /**
     * kill the jobs
     */
    private fun kill() {
        playerRemoveJobs.values.forEach { it.cancel() }
        timerJob?.cancel()
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

        const val PLAYER_REMOVE_TIME = 60000L

        const val DELAY_WAITING_FOR_START_TO_NEW_ROUND = 10000L
        const val DELAY_NEW_ROUND_TO_GAME_RUNNING = 20000L
        const val DELAY_GAME_RUNNING_TO_SHOW_WORD = 60000L
        const val DELAY_SHOW_WORD_TO_NEW_ROUND = 10000L

        const val PENALTY_NOBODY_GUESSED_IT = 50
        const val GUESS_SCORE_DEFAULT = 50
        const val GUESS_SCORE_PERCENTAGE_MULTIPLIER = 50
        const val GUESS_SCORE_FOR_DRAWING_PLAYER = 50
    }
}