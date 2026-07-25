package com.catan.core

import com.catan.core.model.GameState
import com.catan.core.model.Resource
import com.catan.core.net.CatanJson
import com.catan.core.rules.BankTrade
import com.catan.core.rules.BuildSettlement
import com.catan.core.rules.EndTurn
import com.catan.core.rules.GameAction
import com.catan.core.rules.MoveRobber
import com.catan.core.rules.PlayYearOfPlenty
import com.catan.core.rules.RollDice
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SerializationTest {

    @Test
    fun `a full game state survives a round trip`() {
        val original = startedGame(4)
        val json = CatanJson.encodeToString(original)
        val restored = CatanJson.decodeFromString<GameState>(json)

        assertEquals(original, restored)
        assertEquals(original.board.tiles, restored.board.tiles)
        assertEquals(original.roads, restored.roads)
        assertEquals(original.buildings, restored.buildings)
        assertEquals(original.board.harbors, restored.board.harbors)
    }

    @Test
    fun `mid-game state with roads and buildings survives a round trip`() {
        var state = startedGame(3)
        state = state.expectOk(state.currentPlayer.id, RollDice, ScriptedRandom(diceFor(8)))
        val json = CatanJson.encodeToString(state)
        assertEquals(state, CatanJson.decodeFromString<GameState>(json))
    }

    @Test
    fun `every action type survives a round trip`() {
        val state = startedGame()
        val actions: List<GameAction> = listOf(
            RollDice,
            EndTurn,
            BuildSettlement(state.board.vertices.first()),
            MoveRobber(state.board.hexes.first()),
            BankTrade(Resource.ORE, Resource.WOOL),
            PlayYearOfPlenty(Resource.ORE, Resource.GRAIN),
        )
        for (action in actions) {
            val json = CatanJson.encodeToString(action)
            assertEquals(action, CatanJson.decodeFromString<GameAction>(json)) { json }
        }
    }

    @Test
    fun `structured map keys are actually encoded`() {
        val state = startedGame()
        val json = CatanJson.encodeToString(state)
        assertTrue(json.contains("\"roads\"")) { "roads missing from the payload" }
        assertTrue(state.roads.isNotEmpty())
    }
}
