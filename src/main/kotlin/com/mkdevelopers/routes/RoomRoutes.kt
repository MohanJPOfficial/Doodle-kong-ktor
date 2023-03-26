package com.mkdevelopers.routes

import com.mkdevelopers.data.Room
import com.mkdevelopers.data.models.BasicApiResponse
import com.mkdevelopers.data.models.CreateRoomRequest
import com.mkdevelopers.data.models.RoomResponse
import com.mkdevelopers.server
import com.mkdevelopers.util.Constants
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

//Respond to the create room HTTP request
fun Route.createRoomRoute() {

    route("/api/createRoom") {
        post {
            val roomRequest = call.receiveOrNull<CreateRoomRequest>()

            //bad request
            if(roomRequest == null) {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }

            //room already exist
            if(server.rooms[roomRequest.name] != null) {
                call.respond(
                    HttpStatusCode.OK,
                    BasicApiResponse(false, "Room already exists.")
                )
                return@post
            }

            if(roomRequest.maxPlayers < 2) {
                call.respond(
                    HttpStatusCode.OK,
                    BasicApiResponse(false, "The minimum room size is 2.")
                )
                return@post
            }

            if(roomRequest.maxPlayers > Constants.MAX_ROOM_SIZE) {
                call.respond(
                    HttpStatusCode.OK,
                    BasicApiResponse(false, "The maximum room size is ${Constants.MAX_ROOM_SIZE}.")
                )
                return@post
            }

            //creating a room
            val room = Room(
                roomRequest.name,
                roomRequest.maxPlayers
            )
            server.rooms[roomRequest.name] = room

            println("Room created: ${roomRequest.name}")

            call.respond(HttpStatusCode.OK, BasicApiResponse(true))
        }
    }
}

//Respond to the search room HTTP request
fun Route.getRoomsRoute() {
    route("/api/getRooms") {
        get {
            val searchQuery = call.parameters["searchQuery"]

            if(searchQuery == null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    BasicApiResponse(false, "The search query is null.")
                )
                return@get
            }

            val roomsResult = server.rooms.filterKeys {
                it.contains(searchQuery, ignoreCase = true)
            }

            val roomResponses = roomsResult.values.map {
                RoomResponse(it.name, it.maxPlayers, it.players.size)
            }.sortedBy { it.name }

            call.respond(HttpStatusCode.OK, roomResponses)
        }
    }
}

//Respond to the join room HTTP request
fun Route.joinRoomRoute() {
    route("/api/joinRoom") {
        get {
            val userName = call.parameters["username"]
            val roomName = call.parameters["roomName"]

            if(userName == null || roomName == null){
                call.respond(
                    HttpStatusCode.BadRequest,
                    BasicApiResponse(false, "The user name or room name is null")
                )
                return@get
            }

            val room = server.rooms[roomName]
            when {
                room == null -> {
                    call.respond(
                        HttpStatusCode.OK,
                        BasicApiResponse(false, "Room not found")
                    )
                }
                room.containsPlayer(userName) -> {
                    call.respond(
                        HttpStatusCode.OK,
                        BasicApiResponse(false, "A player with this username already joined.")
                    )
                }
                room.players.size >= room.maxPlayers -> {
                    call.respond(
                        HttpStatusCode.OK,
                        BasicApiResponse(false, "This room is already full.")
                    )
                }
                else -> {
                    call.respond(
                        HttpStatusCode.OK,
                        BasicApiResponse(true)
                    )
                }
            }
        }
    }
}
