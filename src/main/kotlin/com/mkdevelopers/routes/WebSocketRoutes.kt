package com.mkdevelopers.routes

import com.google.gson.JsonParser
import com.mkdevelopers.data.Player
import com.mkdevelopers.data.Room
import com.mkdevelopers.data.models.*
import com.mkdevelopers.gson
import com.mkdevelopers.server
import com.mkdevelopers.session.DrawingSession
import com.mkdevelopers.util.Constants
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.consumeEach

fun Route.gameWebSocketRoute() {
    route("/ws/draw") {
        standardWebSocket { socket, clientId, message, payload ->
            when(payload) {
                is JonRoomHandshake -> {
                    val room = server.rooms[payload.roomName]
                    if(room == null) {
                        val gameError = GameError(GameError.ERROR_ROOM_NOT_FOUND)
                        socket.send(Frame.Text(gson.toJson(gameError)))
                        return@standardWebSocket
                    }

                    val player = Player(
                        userName = payload.userName,
                        socket = socket,
                        clientId = payload.clientId
                    )

                    //adding player to overall players
                    server.playerJoined(player)

                    //adding player to particular room
                    if(!room.containsPlayer(player.userName)) {
                        room.addPlayer(
                            clientId = player.clientId,
                            username = player.userName,
                            socketSession = socket
                        )
                    }
                }
                is DrawData -> {
                    val room = server.rooms[payload.roomName] ?: return@standardWebSocket
                    if(room.phase == Room.Phase.GAME_RUNNING) {
                        room.broadcastToAllExcept(message, clientId)
                    }
                }
                is ChosenWord -> {
                    val room = server.rooms[payload.roomName] ?: return@standardWebSocket
                    room.setWordAndSwitchToGameRunning(payload.chosenWord)
                }
                is ChatMessage -> {
                    val room = server.rooms[payload.roomName] ?: return@standardWebSocket

                    //sending normal chat messages
                    if(!room.checkWordAndNotifyPlayers(payload)) {
                        room.broadcast(message)
                    }
                }
            }
        }
    }
}

fun Route.standardWebSocket(
    handleFrame: suspend (
        socket: DefaultWebSocketServerSession,
        clientId: String,
        message: String,
        payload: BaseModel
    ) -> Unit
) {
    webSocket {
        val session = call.sessions.get<DrawingSession>()

        //no session
        if(session == null) {
            close(
                CloseReason(CloseReason.Codes.VIOLATED_POLICY, "No session.")
            )
            return@webSocket
        }

        /**
         * session is not null
         * client sending data through session by frame type
         * now handle the client request
         */
        try {
            incoming.consumeEach { frame ->
                if(frame is Frame.Text) {
                    val message = frame.readText()
                    val jsonObject = JsonParser.parseString(message).asJsonObject
                    val type = when(jsonObject.get("type").asString) {
                        Constants.TYPE_CHAT_MESSAGE -> ChatMessage::class.java
                        Constants.TYPE_DRAW_DATA -> DrawData::class.java
                        Constants.TYPE_ANNOUNCEMENT -> Announcement::class.java
                        Constants.TYPE_JOIN_ROOM_HANDSHAKE -> JonRoomHandshake::class.java
                        Constants.TYPE_PHASE_CHANGE -> PhaseChange::class.java
                        Constants.TYPE_CHOSEN_WORD -> ChosenWord::class.java
                        Constants.TYPE_GAME_STATE -> GameState::class.java
                        else -> BaseModel::class.java
                    }
                    val payload = gson.fromJson(message, type)
                    handleFrame(
                        this,
                        session.clientId,
                        message,
                        payload
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            //handle disconnects
        }
    }
}