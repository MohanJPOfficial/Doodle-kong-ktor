package com.mkdevelopers.data.models

import com.mkdevelopers.data.Room
import com.mkdevelopers.util.Constants

data class PhaseChange(
    var phase: Room.Phase?,
    var time: Long,
    val drawingPlayer: String? = null
): BaseModel(Constants.TYPE_PHASE_CHANGE)
