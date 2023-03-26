package com.mkdevelopers

import com.mkdevelopers.data.Player
import com.mkdevelopers.data.Room
import java.util.concurrent.ConcurrentHashMap

// Used for to manage all rooms and players
class DrawingServer {

    val rooms = ConcurrentHashMap<String, Room>()
    val players = ConcurrentHashMap<String, Player>()

    fun playerJoined(player: Player) {
        players[player.clientId] = player
    }

    fun getRoomWithClientId(clientId: String): Room? {
        val filteredRooms = rooms.filterValues { room ->
            room.players.find { player ->
                player.clientId == clientId
            } != null
        }

        return if(filteredRooms.values.isEmpty())
            null
        else
            filteredRooms.values.toList().first()
    }
}