package com.mkdevelopers.data

import com.mkdevelopers.data.models.Ping
import com.mkdevelopers.gson
import com.mkdevelopers.server
import com.mkdevelopers.util.Constants
import io.ktor.websocket.*
import kotlinx.coroutines.*

data class Player(
    val userName: String,
    var socket: WebSocketSession,
    val clientId: String,
    var isDrawing: Boolean = false,
    var score: Int = 0,
    var rank: Int = 0
) {
    private var pingJob: Job? = null

    private var pingTime = 0L
    private var pongTIme = 0L

    var isOnline = true

    @OptIn(DelicateCoroutinesApi::class)
    fun startPinging() {
        pingJob?.cancel()
        pingJob = GlobalScope.launch {
            while(true) {
                sendPing()
                delay(Constants.PING_FREQUENCY)
            }
        }
    }

    private suspend fun sendPing() {
        pingTime = System.currentTimeMillis()
        socket.send(Frame.Text(gson.toJson(Ping())))
        delay(Constants.PING_FREQUENCY)

        if(pingTime - pongTIme > Constants.PING_FREQUENCY) {
            isOnline = false
            server.playerLeft(clientId)
            pingJob?.cancel()
        }
    }

    fun receivedPong() {
        pongTIme = System.currentTimeMillis()
        isOnline = true
    }

    fun disconnect() {
        pingJob?.cancel()
    }
}
