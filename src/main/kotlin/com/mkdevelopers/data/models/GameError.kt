package com.mkdevelopers.data.models

import com.mkdevelopers.util.Constants

data class GameError(
    val errorType: Int
): BaseModel(Constants.TYPE_GAME_ERROR) {

    companion object {

        const val ERROR_ROOM_NOT_FOUND = 0
    }
}
