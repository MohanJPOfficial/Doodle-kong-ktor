package com.mkdevelopers

import com.google.gson.Gson
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import com.mkdevelopers.plugins.*
import io.ktor.util.*

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

val server = DrawingServer()
val gson = Gson()

fun Application.module() {
    configureSessions()
    configureSockets()
    configureSerialization()
    configureMonitoring()
    configureRouting()
}
