package com.catan.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.catan.app.net.Connection
import com.catan.core.model.PlayerColor
import com.catan.core.net.LobbyView

fun colorOf(color: PlayerColor): Color = when (color) {
    PlayerColor.RED -> Color(0xFFD32F2F)
    PlayerColor.BLUE -> Color(0xFF1976D2)
    PlayerColor.WHITE -> Color(0xFFECECEC)
    PlayerColor.ORANGE -> Color(0xFFF57C00)
}

@Composable
fun ConnectScreen(
    connection: Connection,
    onHost: (server: String, name: String) -> Unit,
    onJoin: (server: String, code: String, name: String) -> Unit,
) {
    var server by remember { mutableStateOf("10.0.2.2:8080") }
    var name by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }

    Surface(color = Color(0xFF0E3A5A), modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Card(Modifier.width(420.dp).padding(24.dp)) {
                Column(
                    Modifier.padding(24.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Catan", fontSize = 34.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Enter the address of your game server, then host a table or join one " +
                            "with its code.",
                        fontSize = 13.sp,
                    )

                    OutlinedTextField(
                        value = server,
                        onValueChange = { server = it },
                        label = { Text("Server") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(16) },
                        label = { Text("Your name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Button(
                        onClick = { onHost(server, name.ifBlank { "Player" }) },
                        enabled = server.isNotBlank() && connection !is Connection.Connecting,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Host a new game") }

                    Spacer(Modifier.height(4.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = code,
                            onValueChange = { code = it.take(4).uppercase() },
                            label = { Text("Room code") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(
                            onClick = { onJoin(server, code, name.ifBlank { "Player" }) },
                            enabled = code.length == 4 && connection !is Connection.Connecting,
                        ) { Text("Join") }
                    }

                    when (connection) {
                        is Connection.Connecting -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text("Connecting...", fontSize = 13.sp)
                        }

                        is Connection.Failed -> Text(
                            connection.message,
                            color = Color(0xFFB00020),
                            fontSize = 13.sp,
                        )

                        else -> Unit
                    }
                }
            }
        }
    }
}

@Composable
fun LobbyScreen(lobby: LobbyView, onStart: () -> Unit, onLeave: () -> Unit) {
    Surface(color = Color(0xFF0E3A5A), modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Card(Modifier.width(440.dp).padding(24.dp)) {
                Column(
                    Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Room code", fontSize = 13.sp)
                    Text(
                        lobby.roomCode,
                        fontSize = 44.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Share this code with the other players.",
                        fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )

                    Spacer(Modifier.height(8.dp))
                    Text("Players (${lobby.seats.size}/4)", fontWeight = FontWeight.Bold)

                    for (seat in lobby.seats) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(
                                Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(colorOf(seat.color)),
                            )
                            Text(seat.name, fontWeight = FontWeight.Medium)
                            if (seat.isHost) Text("host", fontSize = 12.sp)
                            if (seat.playerId == lobby.you) Text("you", fontSize = 12.sp)
                            if (!seat.connected) Text("offline", fontSize = 12.sp)
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onStart,
                            enabled = lobby.canStart,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                if (lobby.seats.size < 2) {
                                    "Waiting for players"
                                } else if (lobby.canStart) {
                                    "Start game"
                                } else {
                                    "Waiting for the host"
                                },
                            )
                        }
                        OutlinedButton(onClick = onLeave) { Text("Leave") }
                    }
                }
            }
        }
    }
}
