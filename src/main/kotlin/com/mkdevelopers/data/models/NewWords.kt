package com.mkdevelopers.data.models

import com.mkdevelopers.util.Constants

data class NewWords(
    val newWords: List<String>
): BaseModel(Constants.TYPE_NEW_WORDS)