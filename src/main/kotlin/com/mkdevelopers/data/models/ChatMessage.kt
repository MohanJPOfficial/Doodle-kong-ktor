package com.mkdevelopers.data.models

import com.mkdevelopers.util.Constants

data class ChatMessage(
    val from: String,
    val roomName: String,
    val message: String,
    val timeStamp: Long
) : BaseModel(Constants.TYPE_CHAT_MESSAGE)