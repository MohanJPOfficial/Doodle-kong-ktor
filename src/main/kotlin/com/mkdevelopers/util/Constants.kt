package com.mkdevelopers.util

object Constants {

    const val MAX_ROOM_SIZE = 8

    //request types from client side
    const val TYPE_CHAT_MESSAGE = "TYPE_CHAT_MESSAGE"
    const val TYPE_DRAW_DATA = "TYPE_DRAW_DATA"
    const val TYPE_ANNOUNCEMENT = "TYPE_ANNOUNCEMENT"
    const val TYPE_JOIN_ROOM_HANDSHAKE = "TYPE_JOIN_ROOM_HANDSHAKE"

    //only sent by server side
    const val TYPE_GAME_ERROR = "TYPE_GAME_ERROR"

    const val TYPE_PHASE_CHANGE = "TYPE_PHASE_CHANGE"
}