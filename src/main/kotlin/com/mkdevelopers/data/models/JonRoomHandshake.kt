package com.mkdevelopers.data.models

import com.mkdevelopers.util.Constants

data class JonRoomHandshake(
    val userName: String,
    val roomName: String,
    val clientId: String,
): BaseModel(Constants.TYPE_JOIN_ROOM_HANDSHAKE)
