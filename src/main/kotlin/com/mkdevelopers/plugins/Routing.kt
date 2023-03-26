package com.mkdevelopers.plugins

import com.mkdevelopers.routes.createRoomRoute
import com.mkdevelopers.routes.gameWebSocketRoute
import com.mkdevelopers.routes.getRoomsRoute
import com.mkdevelopers.routes.joinRoomRoute
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.application.*

fun Application.configureRouting() {

    /*routing {
        get("/") {
            call.respondText("Hello World!")
        }
    }*/

    install(Routing) {
        createRoomRoute()
        getRoomsRoute()
        joinRoomRoute()
        gameWebSocketRoute()
    }
}
