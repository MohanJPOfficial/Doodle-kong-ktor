package com.mkdevelopers.data.models

import com.mkdevelopers.util.Constants

data class ChosenWord(
    val chosenWord: String,
    val roomName: String
): BaseModel(Constants.TYPE_CHOSEN_WORD)
