package com.catan.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import com.catan.core.model.DevCardType
import com.catan.core.model.OwnedDevCard
import com.catan.core.model.PlayerId
import com.catan.core.model.Resource
import com.catan.core.model.ResourceCounts
import com.catan.core.model.total
import com.catan.core.net.PlayerView

@Composable
fun ResourceChip(
    resource: Resource,
    count: Int,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(Art.card(resource)),
            contentDescription = resource.name,
            modifier = Modifier.size(34.dp),
            contentScale = ContentScale.Fit,
        )
        Text("$count", fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

/** Forced discard after a seven. The player picks exactly half their hand, rounded down. */
@Composable
fun DiscardDialog(
    hand: ResourceCounts,
    required: Int,
    onConfirm: (ResourceCounts) -> Unit,
) {
    var chosen by remember { mutableStateOf(Resource.entries.associateWith { 0 }) }
    val total = chosen.total

    AlertDialog(
        onDismissRequest = { },
        title = { Text("Discard $required cards") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("You are holding ${hand.total} cards. Choose $required to return.")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (resource in Resource.entries) {
                        val held = hand[resource] ?: 0
                        if (held == 0) continue
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Image(
                                painter = painterResource(Art.card(resource)),
                                contentDescription = resource.name,
                                modifier = Modifier.size(38.dp),
                            )
                            Text("${chosen[resource] ?: 0} / $held", fontSize = 12.sp)
                            Row {
                                TextButton(
                                    onClick = {
                                        val current = chosen[resource] ?: 0
                                        if (current > 0) {
                                            chosen = chosen + (resource to current - 1)
                                        }
                                    },
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
                                ) { Text("-") }
                                TextButton(
                                    onClick = {
                                        val current = chosen[resource] ?: 0
                                        if (current < held && total < required) {
                                            chosen = chosen + (resource to current + 1)
                                        }
                                    },
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
                                ) { Text("+") }
                            }
                        }
                    }
                }
                Text("Selected $total of $required", fontWeight = FontWeight.Bold)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(chosen) },
                enabled = total == required,
            ) { Text("Discard") }
        },
    )
}

/** Which player to rob, when the robber lands on a tile touching more than one. */
@Composable
fun StealDialog(
    view: PlayerView,
    candidates: Set<PlayerId>,
    onPick: (PlayerId) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { },
        title = { Text("Steal from") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (id in candidates) {
                    val player = view.state.players.first { it.id == id }
                    OutlinedButton(
                        onClick = { onPick(id) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Box(
                            Modifier
                                .size(14.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(colorOf(player.color)),
                        )
                        Text(
                            "  ${player.name}  (${view.handSizes[id] ?: 0} cards)",
                        )
                    }
                }
            }
        },
        confirmButton = { },
    )
}

@Composable
fun DevCardDialog(
    cards: List<OwnedDevCard>,
    turnNumber: Int,
    alreadyPlayedThisTurn: Boolean,
    onPlay: (DevCardType) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Development cards") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (cards.isEmpty()) Text("You have no development cards.")
                for (card in cards) {
                    val fresh = card.boughtOnTurn >= turnNumber
                    val playable = !card.played &&
                        !fresh &&
                        !alreadyPlayedThisTurn &&
                        card.type != DevCardType.VICTORY_POINT

                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Image(
                                painter = painterResource(Art.devCard(card.type)),
                                contentDescription = card.type.name,
                                modifier = Modifier.size(48.dp),
                                contentScale = ContentScale.Fit,
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    card.type.name.lowercase()
                                        .replace('_', ' ')
                                        .replaceFirstChar { it.uppercase() },
                                    fontWeight = FontWeight.Bold,
                                )
                                val note = when {
                                    card.played -> "Already played"
                                    card.type == DevCardType.VICTORY_POINT ->
                                        "Counts as 1 point, kept hidden"
                                    fresh -> "Bought this turn"
                                    alreadyPlayedThisTurn -> "One card per turn"
                                    else -> "Ready to play"
                                }
                                Text(note, fontSize = 12.sp)
                            }
                            if (playable) {
                                TextButton(onClick = { onPlay(card.type) }) { Text("Play") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** Picks one or two resources, for Year of Plenty and Monopoly. */
@Composable
fun PickResourcesDialog(
    title: String,
    count: Int,
    onConfirm: (List<Resource>) -> Unit,
    onDismiss: () -> Unit,
) {
    var picked by remember { mutableStateOf(listOf<Resource>()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (resource in Resource.entries) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    if (picked.size < count) picked = picked + resource
                                }
                                .padding(4.dp),
                        ) {
                            Image(
                                painter = painterResource(Art.card(resource)),
                                contentDescription = resource.name,
                                modifier = Modifier.size(40.dp),
                            )
                            Text(
                                resource.name.lowercase().replaceFirstChar { it.uppercase() },
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
                Text(
                    if (picked.isEmpty()) {
                        "Choose $count."
                    } else {
                        "Chosen: " + picked.joinToString(", ") { it.name.lowercase() }
                    },
                )
                if (picked.isNotEmpty()) {
                    TextButton(onClick = { picked = emptyList() }) { Text("Clear") }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(picked) },
                enabled = picked.size == count,
            ) { Text("Confirm") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Trade with the bank at whatever rate the player's harbours allow. */
@Composable
fun BankTradeDialog(
    view: PlayerView,
    onTrade: (give: Resource, receive: Resource) -> Unit,
    onDismiss: () -> Unit,
) {
    val me = view.you
    val hand = view.state.player(me).resources
    var give by remember { mutableStateOf<Resource?>(null) }
    var receive by remember { mutableStateOf<Resource?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Trade with the bank") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Give", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (resource in Resource.entries) {
                        val rate = view.state.tradeRatio(me, resource)
                        val affordable = (hand[resource] ?: 0) >= rate
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    if (give == resource) Color(0x3300AA00) else Color.Transparent,
                                )
                                .clickable(enabled = affordable) { give = resource }
                                .padding(4.dp),
                        ) {
                            Image(
                                painter = painterResource(Art.card(resource)),
                                contentDescription = resource.name,
                                modifier = Modifier.size(36.dp),
                            )
                            Text("$rate:1", fontSize = 11.sp)
                            Text("have ${hand[resource] ?: 0}", fontSize = 10.sp)
                        }
                    }
                }

                Text("Receive", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (resource in Resource.entries) {
                        val available = (view.state.bank[resource] ?: 0) > 0
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    if (receive == resource) {
                                        Color(0x3300AA00)
                                    } else {
                                        Color.Transparent
                                    },
                                )
                                .clickable(enabled = available && resource != give) {
                                    receive = resource
                                }
                                .padding(4.dp),
                        ) {
                            Image(
                                painter = painterResource(Art.card(resource)),
                                contentDescription = resource.name,
                                modifier = Modifier.size(36.dp),
                            )
                            Text("bank ${view.state.bank[resource] ?: 0}", fontSize = 10.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val g = give
                    val r = receive
                    if (g != null && r != null) onTrade(g, r)
                },
                enabled = give != null && receive != null && give != receive,
            ) { Text("Trade") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
