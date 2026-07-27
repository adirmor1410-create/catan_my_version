package com.catan.app.net

import com.catan.core.model.GameState
import com.catan.core.model.PlayerColor
import com.catan.core.model.PlayerId
import com.catan.core.net.CatanJson
import com.catan.core.net.LobbySeat
import com.catan.core.net.LobbyView
import com.catan.core.net.PlayerView
import com.catan.core.net.redactFor
import com.catan.core.rules.ActionResult
import com.catan.core.rules.GameAction
import com.catan.core.rules.GameEngine

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString

/**
 * Handles multiplayer game synchronization using Firebase Realtime Database.
 * No local server required!
 */
class FirebaseGameClient {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val db: FirebaseDatabase by lazy { FirebaseDatabase.getInstance() }

    private val _connection = MutableStateFlow<Connection>(Connection.Offline)
    val connection: StateFlow<Connection> = _connection.asStateFlow()

    private val _lobby = MutableStateFlow<LobbyView?>(null)
    val lobby: StateFlow<LobbyView?> = _lobby.asStateFlow()

    private val _view = MutableStateFlow<PlayerView?>(null)
    val view: StateFlow<PlayerView?> = _view.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private var currentRoomCode: String? = null
    private var myPlayerId: PlayerId? = null
    private var myPlayerName: String = "Player"
    private var listener: ValueEventListener? = null
    private var currentFullState: GameState? = null

    fun clearNotice() {
        _notice.value = null
    }

    fun host(playerName: String) = scope.launch {
        myPlayerName = playerName.ifBlank { "Player" }
        _connection.value = Connection.Connecting

        val roomCode = generateRoomCode()
        currentRoomCode = roomCode
        val pid = PlayerId(1)
        myPlayerId = pid

        val hostSeat = mapOf(
            "playerId" to pid.value,
            "name" to myPlayerName,
            "color" to PlayerColor.RED.name,
            "isHost" to true,
            "connected" to true
        )


        val roomData = mapOf(
            "roomCode" to roomCode,
            "status" to "LOBBY",
            "hostId" to pid.value,
            "seats" to listOf(hostSeat),
            "gameStateJson" to null
        )

        val ref = db.getReference("rooms").child(roomCode)
        ref.setValue(roomData).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                _connection.value = Connection.Online
                attachListener(roomCode)
            } else {
                _connection.value = Connection.Failed(
                    task.exception?.message ?: "Failed to create room on Firebase."
                )
            }
        }
    }

    fun join(code: String, playerName: String) = scope.launch {
        val trimmedCode = code.trim().uppercase()
        if (trimmedCode.length != 4) {
            _notice.value = "Room code must be 4 characters."
            return@launch
        }

        myPlayerName = playerName.ifBlank { "Player" }
        _connection.value = Connection.Connecting

        val ref = db.getReference("rooms").child(trimmedCode)
        ref.get().addOnSuccessListener { snapshot ->
            if (!snapshot.exists()) {
                _connection.value = Connection.Failed("Room $trimmedCode not found.")
                return@addOnSuccessListener
            }

            val status = snapshot.child("status").getValue(String::class.java)
            if (status != "LOBBY") {
                _connection.value = Connection.Failed("Game has already started.")
                return@addOnSuccessListener
            }

            val seatsSnap = snapshot.child("seats")
            val currentSeatsCount = seatsSnap.childrenCount.toInt()
            if (currentSeatsCount >= 4) {
                _connection.value = Connection.Failed("Room is full.")
                return@addOnSuccessListener
            }

            val availableColors = PlayerColor.entries - seatsSnap.children.mapNotNull {
                it.child("color").getValue(String::class.java)?.let { c -> runCatching { PlayerColor.valueOf(c) }.getOrNull() }
            }.toSet()

            val color = availableColors.firstOrNull() ?: PlayerColor.BLUE
            val pid = PlayerId(currentSeatsCount + 1)
            myPlayerId = pid

            val newSeat = mapOf(
                "playerId" to pid.value,
                "name" to myPlayerName,
                "color" to color.name,
                "isHost" to false,
                "connected" to true
            )


            ref.child("seats").child(currentSeatsCount.toString()).setValue(newSeat).addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    currentRoomCode = trimmedCode
                    _connection.value = Connection.Online
                    attachListener(trimmedCode)
                } else {
                    _connection.value = Connection.Failed("Failed to join room.")
                }
            }
        }.addOnFailureListener { failure ->
            _connection.value = Connection.Failed(failure.message ?: "Failed to connect to Firebase.")
        }
    }

    fun startGame() = scope.launch {
        val code = currentRoomCode ?: return@launch
        val ref = db.getReference("rooms").child(code)

        ref.get().addOnSuccessListener { snapshot ->
            val seatsSnap = snapshot.child("seats")
            val seats = seatsSnap.children.mapNotNull {
                val name = it.child("name").getValue(String::class.java) ?: "Player"
                val colorStr = it.child("color").getValue(String::class.java) ?: "RED"
                val color = runCatching { PlayerColor.valueOf(colorStr) }.getOrDefault(PlayerColor.RED)
                GameEngine.Seat(name, color)
            }

            if (seats.size < 2) {
                _notice.value = "Need at least 2 players to start."
                return@addOnSuccessListener
            }

            val initialState = GameEngine.newGame(seats)
            val json = CatanJson.encodeToString(initialState)

            val updates = mapOf(
                "status" to "PLAYING",
                "gameStateJson" to json
            )

            ref.updateChildren(updates)
        }
    }

    fun act(action: GameAction) = scope.launch {
        val code = currentRoomCode ?: return@launch
        val pid = myPlayerId ?: return@launch
        val state = currentFullState ?: return@launch

        val result = GameEngine.apply(state, pid, action)
        if (result is ActionResult.Success) {
            val json = CatanJson.encodeToString(result.state)
            db.getReference("rooms").child(code).child("gameStateJson").setValue(json)
        } else if (result is ActionResult.Rejected) {
            _notice.value = result.reason
        }
    }


    fun leave() = scope.launch {
        detachListener()
        _lobby.value = null
        _view.value = null
        _connection.value = Connection.Offline
        currentRoomCode = null
        myPlayerId = null
        currentFullState = null
    }

    private fun attachListener(roomCode: String) {
        detachListener()
        val ref = db.getReference("rooms").child(roomCode)

        val newListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) {
                    _notice.value = "Room was closed."
                    leave()
                    return
                }

                val status = snapshot.child("status").getValue(String::class.java) ?: "LOBBY"
                val pid = myPlayerId ?: return

                val seatsList = mutableListOf<LobbySeat>()
                val connectedMap = mutableMapOf<PlayerId, Boolean>()

                for (seatSnap in snapshot.child("seats").children) {
                    val spidInt = seatSnap.child("playerId").getValue(Long::class.java)?.toInt()
                        ?: seatSnap.child("playerId").getValue(Int::class.java)
                        ?: continue
                    val spid = PlayerId(spidInt)
                    val name = seatSnap.child("name").getValue(String::class.java) ?: "Player"

                    val colorStr = seatSnap.child("color").getValue(String::class.java) ?: "RED"
                    val color = runCatching { PlayerColor.valueOf(colorStr) }.getOrDefault(PlayerColor.RED)
                    val isHost = seatSnap.child("isHost").getValue(Boolean::class.java) ?: false
                    val connected = seatSnap.child("connected").getValue(Boolean::class.java) ?: true

                    seatsList.add(LobbySeat(spid, name, color, isHost, connected))
                    connectedMap[spid] = connected
                }

                if (status == "LOBBY") {
                    val canStart = seatsList.find { it.playerId == pid }?.isHost == true && seatsList.size >= 2
                    _lobby.value = LobbyView(
                        roomCode = roomCode,
                        seats = seatsList,
                        canStart = canStart,
                        you = pid
                    )
                    _view.value = null
                } else if (status == "PLAYING") {
                    val json = snapshot.child("gameStateJson").getValue(String::class.java)
                    if (json != null) {
                        runCatching {
                            val fullState = CatanJson.decodeFromString<GameState>(json)
                            currentFullState = fullState
                            _view.value = redactFor(fullState, pid, connectedMap)
                            _lobby.value = null
                        }.onFailure {
                            _notice.value = "Failed to parse game update."
                        }
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                _notice.value = error.message
                _connection.value = Connection.Failed(error.message)
            }
        }

        listener = newListener
        ref.addValueEventListener(newListener)
    }

    private fun detachListener() {
        val code = currentRoomCode
        val l = listener
        if (code != null && l != null) {
            db.getReference("rooms").child(code).removeEventListener(l)
        }
        listener = null
    }

    private fun generateRoomCode(): String {
        val chars = "BCDFGHJKLMNPQRSTVWXYZ23456789"
        return (1..4).map { chars.random() }.joinToString("")
    }
}
