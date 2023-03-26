package com.mkdevelopers.data.models

import com.mkdevelopers.util.Constants

data class PlayersList(
    val players: List<PlayerData>
): BaseModel(Constants.TYPE_PLAYERS_LIST)
