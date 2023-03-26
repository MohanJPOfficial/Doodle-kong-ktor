package com.mkdevelopers.data.models

import com.mkdevelopers.util.Constants

data class GameState(
    val drawingPlayer: String,
    val word: String
): BaseModel(Constants.TYPE_GAME_STATE)
