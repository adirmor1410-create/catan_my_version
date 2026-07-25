package com.catan.core.rules

import com.catan.core.model.EdgeId
import com.catan.core.model.GameState
import com.catan.core.model.Hex
import com.catan.core.model.PlayerId
import com.catan.core.model.Resource
import com.catan.core.model.ResourceCounts
import com.catan.core.model.VertexId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Everything a player is ever allowed to ask the game to do. */
@Serializable
sealed interface GameAction

@Serializable
@SerialName("setup_settlement")
data class SetupSettlement(val vertex: VertexId) : GameAction

@Serializable
@SerialName("setup_road")
data class SetupRoad(val edge: EdgeId) : GameAction

@Serializable
@SerialName("roll")
data object RollDice : GameAction

@Serializable
@SerialName("build_road")
data class BuildRoad(val edge: EdgeId) : GameAction

@Serializable
@SerialName("build_settlement")
data class BuildSettlement(val vertex: VertexId) : GameAction

@Serializable
@SerialName("build_city")
data class BuildCity(val vertex: VertexId) : GameAction

@Serializable
@SerialName("buy_dev_card")
data object BuyDevCard : GameAction

@Serializable
@SerialName("play_knight")
data object PlayKnight : GameAction

@Serializable
@SerialName("play_road_building")
data object PlayRoadBuilding : GameAction

@Serializable
@SerialName("play_year_of_plenty")
data class PlayYearOfPlenty(val first: Resource, val second: Resource) : GameAction

@Serializable
@SerialName("play_monopoly")
data class PlayMonopoly(val resource: Resource) : GameAction

/** Trade with the bank at the best rate this player's harbours allow. */
@Serializable
@SerialName("bank_trade")
data class BankTrade(val give: Resource, val receive: Resource) : GameAction

@Serializable
@SerialName("offer_trade")
data class OfferTrade(val give: ResourceCounts, val receive: ResourceCounts) : GameAction

@Serializable
@SerialName("respond_trade")
data class RespondToTrade(val accept: Boolean) : GameAction

/** The offering player picks which acceptor to trade with. */
@Serializable
@SerialName("confirm_trade")
data class ConfirmTrade(val with: PlayerId) : GameAction

@Serializable
@SerialName("cancel_trade")
data object CancelTrade : GameAction

@Serializable
@SerialName("discard")
data class Discard(val resources: ResourceCounts) : GameAction

@Serializable
@SerialName("move_robber")
data class MoveRobber(val to: Hex) : GameAction

@Serializable
@SerialName("steal")
data class StealFrom(val victim: PlayerId) : GameAction

@Serializable
@SerialName("end_turn")
data object EndTurn : GameAction

/** The outcome of asking the engine to apply an action. */
sealed interface ActionResult {
    data class Success(val state: GameState) : ActionResult

    /** The action broke a rule. [reason] is safe to show to the player. */
    data class Rejected(val reason: String) : ActionResult
}
