package com.catan.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.catan.core.model.DevCardType
import com.catan.core.model.GamePhase
import com.catan.core.model.PlayerId
import com.catan.core.model.Resource
import com.catan.core.model.ResourceCounts
import com.catan.core.model.Costs
import com.catan.core.model.covers
import com.catan.core.model.total
import com.catan.core.net.PlayerView
import com.catan.core.rules.BankTrade
import com.catan.core.rules.BuildCity
import com.catan.core.rules.BuildRoad
import com.catan.core.rules.BuildSettlement
import com.catan.core.rules.BuyDevCard
import com.catan.core.rules.CancelTrade
import com.catan.core.rules.ConfirmTrade
import com.catan.core.rules.Discard
import com.catan.core.rules.EndTurn
import com.catan.core.rules.GameAction
import com.catan.core.rules.MoveRobber
import com.catan.core.rules.OfferTrade
import com.catan.core.rules.PlayKnight
import com.catan.core.rules.PlayMonopoly
import com.catan.core.rules.PlayRoadBuilding
import com.catan.core.rules.PlayYearOfPlenty
import com.catan.core.rules.Placement
import com.catan.core.rules.RespondToTrade
import com.catan.core.rules.RollDice
import com.catan.core.rules.SetupRoad
import com.catan.core.rules.SetupSettlement
import com.catan.core.rules.StealFrom

@Composable
fun GameScreen(view: PlayerView, notice: String?, onAct: (GameAction) -> Unit, onDismissNotice: () -> Unit) {
    val state = view.state
    val me = view.you
    val myTurn = state.currentPlayer.id == me
    val myPlayer = state.player(me)

    var pendingMode by remember { mutableStateOf(BoardMode.NONE) }
    var showDevCards by remember { mutableStateOf(false) }
    var showBankTrade by remember { mutableStateOf(false) }
    var showOfferTrade by remember { mutableStateOf(false) }
    var pendingCard by remember { mutableStateOf<DevCardType?>(null) }

    // A mode only survives while it still makes sense.
    LaunchedEffect(state.version, state.phase) {
        if (!myTurn) pendingMode = BoardMode.NONE
        if (state.phase != GamePhase.MAIN && pendingMode != BoardMode.NONE) {
            pendingMode = BoardMode.NONE
        }
    }

    // What a tap on the board means right now.
    val mode: BoardMode = when {
        !myTurn -> BoardMode.NONE
        state.phase == GamePhase.SETUP_ROUND_1 || state.phase == GamePhase.SETUP_ROUND_2 ->
            if (state.setupSettlement == null) BoardMode.SETTLEMENT else BoardMode.ROAD

        state.phase == GamePhase.MOVE_ROBBER -> BoardMode.ROBBER
        state.phase == GamePhase.MAIN && state.freeRoadsRemaining > 0 -> BoardMode.ROAD
        state.phase == GamePhase.MAIN -> pendingMode
        else -> BoardMode.NONE
    }

    val legalVertices = when (mode) {
        BoardMode.SETTLEMENT ->
            if (state.phase == GamePhase.SETUP_ROUND_1 || state.phase == GamePhase.SETUP_ROUND_2) {
                Placement.setupSettlementSpots(state)
            } else {
                Placement.settlementSpots(state, me)
            }

        BoardMode.CITY -> Placement.citySpots(state, me)
        else -> emptySet()
    }

    val legalEdges = when (mode) {
        BoardMode.ROAD -> {
            val pending = state.setupSettlement
            if (pending != null) Placement.setupRoadSpots(state, pending) else Placement.roadSpots(state, me)
        }
        else -> emptySet()
    }

    Surface(color = Color(0xFF0B2E47), modifier = Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                BoardView(
                    view = view,
                    mode = mode,
                    legalVertices = legalVertices,
                    legalEdges = legalEdges,
                    onVertexTap = { vertex ->
                        val action = when {
                            state.phase == GamePhase.SETUP_ROUND_1 ||
                                state.phase == GamePhase.SETUP_ROUND_2 -> SetupSettlement(vertex)
                            mode == BoardMode.CITY -> BuildCity(vertex)
                            else -> BuildSettlement(vertex)
                        }
                        onAct(action)
                        pendingMode = BoardMode.NONE
                    },
                    onEdgeTap = { edge ->
                        val action = if (state.setupSettlement != null) {
                            SetupRoad(edge)
                        } else {
                            BuildRoad(edge)
                        }
                        onAct(action)
                        pendingMode = BoardMode.NONE
                    },
                    onHexTap = { hex -> onAct(MoveRobber(hex)) },
                    modifier = Modifier.fillMaxSize(),
                )

                PhaseBanner(
                    view = view,
                    myTurn = myTurn,
                    mode = mode,
                    modifier = Modifier.align(Alignment.TopCenter).padding(8.dp),
                )
            }

            SidePanel(
                view = view,
                myTurn = myTurn,
                pendingMode = pendingMode,
                onSetMode = { pendingMode = if (pendingMode == it) BoardMode.NONE else it },
                onAct = onAct,
                onShowDevCards = { showDevCards = true },
                onShowBankTrade = { showBankTrade = true },
                onShowOfferTrade = { showOfferTrade = true },
                modifier = Modifier.width(300.dp).fillMaxHeight(),
            )
        }
    }

    // ---- forced interactions

    if (state.phase == GamePhase.DISCARD && me in state.pendingDiscards) {
        DiscardDialog(
            hand = myPlayer.resources,
            required = myPlayer.handSize / 2,
            onConfirm = { onAct(Discard(it)) },
        )
    }

    if (state.phase == GamePhase.STEAL && myTurn) {
        StealDialog(view, state.stealCandidates) { onAct(StealFrom(it)) }
    }

    val offer = state.openTrade
    if (offer != null && offer.from != me && me !in offer.acceptedBy && me !in offer.rejectedBy) {
        IncomingTradeDialog(
            view = view,
            onRespond = { accept -> onAct(RespondToTrade(accept)) },
        )
    }
    if (offer != null && offer.from == me) {
        OutgoingTradeDialog(
            view = view,
            onConfirm = { onAct(ConfirmTrade(it)) },
            onCancel = { onAct(CancelTrade) },
        )
    }

    // ---- optional dialogs

    if (showDevCards) {
        DevCardDialog(
            cards = myPlayer.devCards,
            turnNumber = state.turnNumber,
            alreadyPlayedThisTurn = myPlayer.playedDevCardThisTurn,
            onPlay = { type ->
                showDevCards = false
                when (type) {
                    DevCardType.KNIGHT -> onAct(PlayKnight)
                    DevCardType.ROAD_BUILDING -> onAct(PlayRoadBuilding)
                    DevCardType.MONOPOLY, DevCardType.YEAR_OF_PLENTY -> pendingCard = type
                    DevCardType.VICTORY_POINT -> Unit
                }
            },
            onDismiss = { showDevCards = false },
        )
    }

    when (pendingCard) {
        DevCardType.YEAR_OF_PLENTY -> PickResourcesDialog(
            title = "Year of Plenty: take two",
            count = 2,
            onConfirm = {
                onAct(PlayYearOfPlenty(it[0], it[1]))
                pendingCard = null
            },
            onDismiss = { pendingCard = null },
        )

        DevCardType.MONOPOLY -> PickResourcesDialog(
            title = "Monopoly: name a resource",
            count = 1,
            onConfirm = {
                onAct(PlayMonopoly(it[0]))
                pendingCard = null
            },
            onDismiss = { pendingCard = null },
        )

        else -> Unit
    }

    if (showBankTrade) {
        BankTradeDialog(
            view = view,
            onTrade = { give, receive ->
                onAct(BankTrade(give, receive))
                showBankTrade = false
            },
            onDismiss = { showBankTrade = false },
        )
    }

    if (showOfferTrade) {
        OfferTradeDialog(
            view = view,
            onOffer = { give, receive ->
                onAct(OfferTrade(give, receive))
                showOfferTrade = false
            },
            onDismiss = { showOfferTrade = false },
        )
    }

    if (notice != null) {
        AlertDialog(
            onDismissRequest = onDismissNotice,
            title = { Text("Not allowed") },
            text = { Text(notice) },
            confirmButton = { TextButton(onClick = onDismissNotice) { Text("OK") } },
        )
    }

    if (state.phase == GamePhase.GAME_OVER) {
        val winner = state.winner
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Game over") },
            text = {
                Text(
                    if (winner == me) {
                        "You win with ${state.victoryPoints(me)} points."
                    } else if (winner != null) {
                        "${state.player(winner).name} wins."
                    } else {
                        "The game has ended."
                    },
                )
            },
            confirmButton = { },
        )
    }
}

@Composable
private fun PhaseBanner(view: PlayerView, myTurn: Boolean, mode: BoardMode, modifier: Modifier) {
    val state = view.state
    val text = when {
        state.phase == GamePhase.GAME_OVER -> "Game over"
        !myTurn -> "${state.currentPlayer.name}'s turn"
        state.phase == GamePhase.SETUP_ROUND_1 || state.phase == GamePhase.SETUP_ROUND_2 ->
            if (state.setupSettlement == null) {
                "Place a settlement"
            } else {
                "Place a road from it"
            }

        state.phase == GamePhase.ROLL -> "Roll the dice"
        state.phase == GamePhase.MOVE_ROBBER -> "Tap a tile to move the robber"
        state.phase == GamePhase.DISCARD -> "Waiting for discards"
        state.phase == GamePhase.STEAL -> "Choose who to rob"
        state.freeRoadsRemaining > 0 -> "Place ${state.freeRoadsRemaining} free road(s)"
        mode == BoardMode.ROAD -> "Tap a highlighted edge"
        mode == BoardMode.SETTLEMENT -> "Tap a highlighted corner"
        mode == BoardMode.CITY -> "Tap one of your settlements"
        else -> "Your turn"
    }

    Surface(
        color = Color(0xCC000000),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier,
    ) {
        Text(
            text,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun SidePanel(
    view: PlayerView,
    myTurn: Boolean,
    pendingMode: BoardMode,
    onSetMode: (BoardMode) -> Unit,
    onAct: (GameAction) -> Unit,
    onShowDevCards: () -> Unit,
    onShowBankTrade: () -> Unit,
    onShowOfferTrade: () -> Unit,
    modifier: Modifier,
) {
    val state = view.state
    val me = view.you
    val myPlayer = state.player(me)
    val canAct = myTurn && state.phase == GamePhase.MAIN

    Column(
        modifier
            .background(Color(0xFF10405F))
            .verticalScroll(rememberScrollState())
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            DicePair(state.lastRoll, 40.dp)
            Column {
                Text(
                    state.lastRoll?.let { "Rolled ${it.first + it.second}" } ?: "No roll yet",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                )
                Text("Turn ${state.turnNumber}", color = Color(0xFFBBD6E8), fontSize = 12.sp)
            }
        }

        for (player in state.players) {
            PlayerRow(view, player.id, player.id == state.currentPlayer.id)
        }

        Spacer(Modifier.height(2.dp))
        Text("Your cards (${myPlayer.handSize})", color = Color.White, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (resource in Resource.entries) {
                ResourceChip(resource, myPlayer.resources[resource] ?: 0)
            }
        }

        if (myPlayer.devCards.isNotEmpty()) {
            OutlinedButton(onClick = onShowDevCards, modifier = Modifier.fillMaxWidth()) {
                Text("Development cards (${myPlayer.devCards.count { !it.played }})")
            }
        }

        Spacer(Modifier.height(2.dp))

        if (myTurn && state.phase == GamePhase.ROLL) {
            Button(onClick = { onAct(RollDice) }, modifier = Modifier.fillMaxWidth()) {
                Text("Roll the dice")
            }
        }

        if (canAct) {
            ActionButton(
                label = "Road",
                cost = "1 brick + 1 lumber",
                enabled = myPlayer.resources.covers(Costs.ROAD) &&
                    myPlayer.roadsLeft > 0 &&
                    Placement.roadSpots(state, me).isNotEmpty(),
                selected = pendingMode == BoardMode.ROAD,
                onClick = { onSetMode(BoardMode.ROAD) },
            )
            ActionButton(
                label = "Settlement",
                cost = "1 brick + 1 lumber + 1 wool + 1 grain",
                enabled = myPlayer.resources.covers(Costs.SETTLEMENT) &&
                    myPlayer.settlementsLeft > 0 &&
                    Placement.settlementSpots(state, me).isNotEmpty(),
                selected = pendingMode == BoardMode.SETTLEMENT,
                onClick = { onSetMode(BoardMode.SETTLEMENT) },
            )
            ActionButton(
                label = "City",
                cost = "2 grain + 3 ore",
                enabled = myPlayer.resources.covers(Costs.CITY) &&
                    myPlayer.citiesLeft > 0 &&
                    Placement.citySpots(state, me).isNotEmpty(),
                selected = pendingMode == BoardMode.CITY,
                onClick = { onSetMode(BoardMode.CITY) },
            )
            ActionButton(
                label = "Development card",
                cost = "1 wool + 1 grain + 1 ore",
                enabled = myPlayer.resources.covers(Costs.DEV_CARD) && view.devDeckSize > 0,
                selected = false,
                onClick = { onAct(BuyDevCard) },
            )

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = onShowBankTrade, modifier = Modifier.weight(1f)) {
                    Text("Bank", fontSize = 13.sp)
                }
                OutlinedButton(
                    onClick = onShowOfferTrade,
                    enabled = myPlayer.handSize > 0 && state.openTrade == null,
                    modifier = Modifier.weight(1f),
                ) { Text("Trade", fontSize = 13.sp) }
            }

            Button(
                onClick = { onAct(EndTurn) },
                enabled = state.freeRoadsRemaining == 0,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("End turn") }
        }

        Spacer(Modifier.height(4.dp))
        Text("Log", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        for (line in state.log.takeLast(12).reversed()) {
            Text(line, color = Color(0xFFCFE3F0), fontSize = 11.sp)
        }
    }
}

@Composable
private fun ActionButton(
    label: String,
    cost: String,
    enabled: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                label,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                fontSize = 13.sp,
            )
            Text(cost, fontSize = 10.sp)
        }
    }
}

@Composable
private fun PlayerRow(view: PlayerView, id: PlayerId, isCurrent: Boolean) {
    val state = view.state
    val player = state.player(id)
    val isMe = id == view.you
    // Only the owner may see points from hidden cards.
    val points = if (isMe) state.victoryPoints(id) else state.publicVictoryPoints(id)

    Card(
        Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(colorOf(player.color)),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    player.name + if (isMe) " (you)" else "",
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 13.sp,
                )
                val offline = if (view.connected[id] == false) "  offline" else ""
                Text(
                    "${view.handSizes[id] ?: 0} cards  " +
                        "${view.devCardCounts[id] ?: 0} dev$offline",
                    fontSize = 10.sp,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("$points vp", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                val badges = buildString {
                    if (state.longestRoadHolder == id) append("road ")
                    if (state.largestArmyHolder == id) append("army")
                }
                if (badges.isNotBlank()) Text(badges, fontSize = 9.sp)
            }
        }
    }
}

@Composable
private fun IncomingTradeDialog(view: PlayerView, onRespond: (Boolean) -> Unit) {
    val offer = view.state.openTrade ?: return
    val from = view.state.player(offer.from)
    val canPay = view.state.player(view.you).resources.covers(offer.receive)

    AlertDialog(
        onDismissRequest = { },
        title = { Text("${from.name} offers a trade") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("They give:", fontWeight = FontWeight.Bold)
                CardRow(offer.give)
                Text("They want:", fontWeight = FontWeight.Bold)
                CardRow(offer.receive)
                if (!canPay) Text("You cannot cover this.", color = Color(0xFFB00020))
            }
        },
        confirmButton = {
            TextButton(onClick = { onRespond(true) }, enabled = canPay) { Text("Accept") }
        },
        dismissButton = { TextButton(onClick = { onRespond(false) }) { Text("Decline") } },
    )
}

@Composable
private fun OutgoingTradeDialog(
    view: PlayerView,
    onConfirm: (PlayerId) -> Unit,
    onCancel: () -> Unit,
) {
    val offer = view.state.openTrade ?: return

    AlertDialog(
        onDismissRequest = { },
        title = { Text("Your offer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("You give:", fontWeight = FontWeight.Bold)
                CardRow(offer.give)
                Text("You want:", fontWeight = FontWeight.Bold)
                CardRow(offer.receive)
                Spacer(Modifier.height(4.dp))
                if (offer.acceptedBy.isEmpty()) {
                    Text("Waiting for someone to accept...")
                } else {
                    Text("Accepted by:", fontWeight = FontWeight.Bold)
                    for (id in offer.acceptedBy) {
                        OutlinedButton(
                            onClick = { onConfirm(id) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Trade with ${view.state.player(id).name}") }
                    }
                }
            }
        },
        confirmButton = { },
        dismissButton = { TextButton(onClick = onCancel) { Text("Withdraw") } },
    )
}

@Composable
private fun OfferTradeDialog(
    view: PlayerView,
    onOffer: (ResourceCounts, ResourceCounts) -> Unit,
    onDismiss: () -> Unit,
) {
    val hand = view.state.player(view.you).resources
    var give by remember { mutableStateOf(Resource.entries.associateWith { 0 }) }
    var want by remember { mutableStateOf(Resource.entries.associateWith { 0 }) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Offer a trade") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("You give", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (resource in Resource.entries) {
                        Counter(
                            resource = resource,
                            value = give[resource] ?: 0,
                            max = hand[resource] ?: 0,
                            onChange = { give = give + (resource to it) },
                        )
                    }
                }
                Text("You want", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (resource in Resource.entries) {
                        Counter(
                            resource = resource,
                            value = want[resource] ?: 0,
                            max = 19,
                            onChange = { want = want + (resource to it) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            val overlap = Resource.entries.any {
                (give[it] ?: 0) > 0 && (want[it] ?: 0) > 0
            }
            TextButton(
                onClick = { onOffer(give.filterValues { it > 0 }, want.filterValues { it > 0 }) },
                enabled = give.total > 0 && want.total > 0 && !overlap,
            ) { Text("Offer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Counter(resource: Resource, value: Int, max: Int, onChange: (Int) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Image(
            painter = painterResource(Art.card(resource)),
            contentDescription = resource.name,
            modifier = Modifier.size(30.dp),
            contentScale = ContentScale.Fit,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "-",
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { if (value > 0) onChange(value - 1) }
                    .padding(horizontal = 6.dp),
            )
            Text("$value", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(
                "+",
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { if (value < max) onChange(value + 1) }
                    .padding(horizontal = 6.dp),
            )
        }
    }
}

@Composable
private fun CardRow(counts: ResourceCounts) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((resource, count) in counts) {
            if (count <= 0) continue
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    painter = painterResource(Art.card(resource)),
                    contentDescription = resource.name,
                    modifier = Modifier.size(30.dp),
                )
                Text("$count", fontSize = 11.sp)
            }
        }
    }
}
