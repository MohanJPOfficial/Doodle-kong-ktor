package com.mkdevelopers.util

import com.mkdevelopers.data.models.ChatMessage

fun ChatMessage.matchesWord(word: String): Boolean {
    return message.lowercase().trim() == word.lowercase().trim()
}