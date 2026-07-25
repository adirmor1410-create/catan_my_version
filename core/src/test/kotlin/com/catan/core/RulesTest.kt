package com.catan.core

import com.catan.core.model.Building
import com.catan.core.model.BuildingType
import com.catan.core.model.Costs
import com.catan.core.model.DevCardType
import com.catan.core.model.GamePhase
import com.catan.core.model.GameState
import com.catan.core.model.Harbor
import com.catan.core.model.Hex
import com.catan.core.model.HexDirection
import com.catan.core.model.OwnedDevCard
import com.catan.core.model.PlayerId
import com.catan.core.model.Resource
import com.catan.core.model.Rules
import com.catan.core.model.Tile
import com.catan.core.model.TileType
import com.catan.core.model.VertexId
import com.catan.core.model.EdgeId
import com.catan.core.model.covers
import com.catan.core.model.emptyResources
import com.catan.core.model.plusCards
import com.catan.core.model.endpoints
import com.catan.core.model.incidentEdges
import com.catan.core.model.total
import com.catan.core.rules.BankTrade
import com.catan.core.rules.BuildCity
import com.catan.core.rules.BuildRoad
import com.catan.core.rules.BuildSettlement
import com.catan.core.rules.BuyDevCard
import com.catan.core.rules.ConfirmTrade
import com.catan.core.rules.Discard
import com.catan.core.rules.EndTurn
import com.catan.core.rules.GameEngine
import com.catan.core.rules.MoveRobber
import com.catan.core.rules.OfferTrade
import com.catan.core.rules.PlayKnight
import com.catan.core.rules.PlayMonopoly
import com.catan.core.rules.PlayRoadBuilding
import com.catan.core.rules.PlayYearOfPlenty
import com.catan.core.rules.Placement
import com.catan.core.rules.RespondToTrade
import com.catan.core.rules.RoadLength
import com.catan.core.rules.RollDice
import com.catan.core.rules.SetupRoad
import com.catan.core.rules.SetupSettlement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.random.Random

class RulesTest {

    private val alice = PlayerId(0)
    private val bob = PlayerId(1)
    private val cara = PlayerId(2)

    // ------------------------------------------------------------------ setup

    @Nested
    inner class Setup {

        @Test
        fun `setup follows snake order and each player places two of each piece`() {
            var state = newGame(3)
            val seatOrder = mutableListOf<Int>()

            while (state.phase == GamePhase.SETUP_ROUND_1 || state.phase == GamePhase.SETUP_ROUND_2) {
                val actor = state.currentPlayer.id
                val pending = state.setupSettlement
                if (pending == null) {
                    seatOrder.add(state.currentPlayerIndex)
                    state = state.expectOk(
                        actor,
                        SetupSettlement(Placement.setupSettlementSpots(state).sorted().first()),
                    )
                } else {
                    state = state.expectOk(
                        actor,
                        SetupRoad(Placement.setupRoadSpots(state, pending).sorted().first()),
                    )
                }
            }

            assertEquals(listOf(0, 1, 2, 2, 1, 0), seatOrder)
            assertEquals(GamePhase.ROLL, state.phase)
            assertEquals(0, state.currentPlayerIndex)
            assertEquals(1, state.turnNumber)
            for (player in state.players) {
                assertEquals(2, state.buildingsOf(player.id).size)
                assertEquals(2, state.roadsOf(player.id).size)
                assertEquals(Rules.MAX_SETTLEMENTS - 2, player.settlementsLeft)
                assertEquals(Rules.MAX_ROADS - 2, player.roadsLeft)
            }
        }

        @Test
        fun `only the second settlement pays out resources`() {
            var state = newGame(3)
            // first placement for Alice
            state = state.expectOk(
                alice,
                SetupSettlement(Placement.setupSettlementSpots(state).sorted().first()),
            )
            assertEquals(0, state.player(alice).handSize)

            state = completeSetup(state)
            // Every player's hand came from exactly their second settlement's tiles.
            for (player in state.players) {
                assertTrue(player.handSize in 1..3) {
                    "${player.name} got ${player.handSize} cards from setup"
                }
            }
            assertCardsConserved(state, "after setup")
        }

        @Test
        fun `a setup road must touch the settlement just placed`() {
            var state = newGame(3)
            val spot = Placement.setupSettlementSpots(state).sorted().first()
            state = state.expectOk(alice, SetupSettlement(spot))

            val touching = state.board.edgesAt(spot).toSet()
            val faraway = state.board.edges.first { it !in touching }
            assertTrue(state.expectRejected(alice, SetupRoad(faraway)).contains("settlement"))
        }

        @Test
        fun `a second settlement cannot be placed before the road`() {
            var state = newGame(3)
            val spots = Placement.setupSettlementSpots(state).sorted()
            state = state.expectOk(alice, SetupSettlement(spots.first()))
            val second = spots.first { state.board.neighborsOf(it).none { n -> n == spots.first() } }
            assertTrue(state.expectRejected(alice, SetupSettlement(second)).contains("road"))
        }

        @Test
        fun `players cannot act out of turn during setup`() {
            val state = newGame(3)
            val spot = Placement.setupSettlementSpots(state).sorted().first()
            assertTrue(state.expectRejected(bob, SetupSettlement(spot)).contains("not your turn"))
        }
    }

    // ------------------------------------------------------------------ placement

    @Nested
    inner class PlacementRules {

        @Test
        fun `the distance rule blocks adjacent corners`() {
            val state = startedGame()
            val taken = state.buildings.keys.first()
            for (neighbor in state.board.neighborsOf(taken)) {
                assertFalse(Placement.satisfiesDistanceRule(state, neighbor)) {
                    "$neighbor is next to an existing building but was allowed"
                }
            }
        }

        @Test
        fun `a settlement needs one of your own roads`() {
            var state = startedGame().giveExactly(
                alice,
                Resource.BRICK to 4,
                Resource.LUMBER to 4,
                Resource.WOOL to 4,
                Resource.GRAIN to 4,
            )
            state = state.copy(phase = GamePhase.MAIN, hasRolled = true)

            val ownRoadCorners = state.roadsOf(alice)
                .flatMap { it.endpoints().toList() }
                .toSet()
            val unreachable = state.board.vertices.first {
                Placement.satisfiesDistanceRule(state, it) && it !in ownRoadCorners
            }
            assertTrue(state.expectRejected(alice, BuildSettlement(unreachable)).contains("road"))
        }

        @Test
        fun `a road cannot be routed through an opponent's settlement`() {
            val centre = Hex(0, 0)
            val blockedCorner = VertexId.corner(centre, HexDirection.E)
            val base = startedGame()

            // Alice owns the edge on one side of the corner, Bob has built on the corner itself.
            val state = base.copy(
                buildings = mapOf(
                    blockedCorner to Building(BuildingType.SETTLEMENT, bob),
                ),
                roads = mapOf(EdgeId.side(centre, HexDirection.E) to alice),
            )

            val beyond = EdgeId.side(centre, HexDirection.NE)
            assertTrue(beyond.endpoints().toList().contains(blockedCorner))
            assertFalse(beyond in Placement.roadSpots(state, alice)) {
                "Alice was allowed to build through Bob's settlement"
            }
            // Bob, who owns the corner, may build there.
            assertTrue(beyond in Placement.roadSpots(state, bob))
        }

        @Test
        fun `a city can only replace your own settlement`() {
            var state = startedGame()
                .giveExactly(alice, Resource.GRAIN to 2, Resource.ORE to 3)
                .copy(phase = GamePhase.MAIN, hasRolled = true)

            val bobsSettlement = state.buildingsOf(bob).keys.first()
            assertTrue(state.expectRejected(alice, BuildCity(bobsSettlement)).contains("own"))

            val own = state.buildingsOf(alice).keys.first()
            state = state.expectOk(alice, BuildCity(own))
            assertEquals(BuildingType.CITY, state.buildings[own]?.type)
            assertEquals(2, state.victoryPoints(alice) - 1) // one city + one settlement = 3
        }

        @Test
        fun `upgrading to a city returns the settlement to the supply`() {
            val state = startedGame()
                .giveExactly(alice, Resource.GRAIN to 2, Resource.ORE to 3)
                .copy(phase = GamePhase.MAIN, hasRolled = true)
            val before = state.player(alice).settlementsLeft

            val own = state.buildingsOf(alice).keys.first()
            val after = state.expectOk(alice, BuildCity(own))
            assertEquals(before + 1, after.player(alice).settlementsLeft)
            assertEquals(Rules.MAX_CITIES - 1, after.player(alice).citiesLeft)
        }
    }

    // ------------------------------------------------------------------ costs

    @Nested
    inner class BuildingCosts {

        @Test
        fun `costs match the printed cards`() {
            assertEquals(2, Costs.ROAD.total)
            assertEquals(1, Costs.ROAD[Resource.BRICK])
            assertEquals(1, Costs.ROAD[Resource.LUMBER])

            assertEquals(4, Costs.SETTLEMENT.total)
            assertEquals(1, Costs.SETTLEMENT[Resource.WOOL])
            assertEquals(1, Costs.SETTLEMENT[Resource.GRAIN])

            assertEquals(5, Costs.CITY.total)
            assertEquals(2, Costs.CITY[Resource.GRAIN])
            assertEquals(3, Costs.CITY[Resource.ORE])

            assertEquals(3, Costs.DEV_CARD.total)
        }

        @Test
        fun `you cannot build without paying`() {
            val state = startedGame()
                .giveExactly(alice)
                .copy(phase = GamePhase.MAIN, hasRolled = true)
            val spot = Placement.roadSpots(state, alice).sorted().first()
            assertTrue(state.expectRejected(alice, BuildRoad(spot)).contains("afford"))
        }

        @Test
        fun `paying for a road returns the cards to the bank`() {
            val state = startedGame()
                .giveExactly(alice, Resource.BRICK to 1, Resource.LUMBER to 1)
                .copy(phase = GamePhase.MAIN, hasRolled = true)
            val bankBefore = state.bank[Resource.BRICK]!!

            val spot = Placement.roadSpots(state, alice).sorted().first()
            val after = state.expectOk(alice, BuildRoad(spot))

            assertEquals(0, after.player(alice).handSize)
            assertEquals(bankBefore + 1, after.bank[Resource.BRICK])
            assertCardsConserved(after)
        }

        @Test
        fun `piece supplies are limited`() {
            val state = startedGame()
                .giveExactly(alice, Resource.BRICK to 5, Resource.LUMBER to 5)
                .copy(phase = GamePhase.MAIN, hasRolled = true)
                .updatePlayer(alice) { it.copy(roadsLeft = 0) }
            val spot = Placement.roadSpots(state, alice).sorted().first()
            assertTrue(state.expectRejected(alice, BuildRoad(spot)).contains("No roads left"))
        }
    }

    // ------------------------------------------------------------------ production

    @Nested
    inner class Production {

        /**
         * A hand-built board so production is completely predictable: only the centre tile
         * shows a 5, every hand is empty and the bank is full.
         */
        private fun riggedState(): GameState {
            val base = startedGame()
            val tiles = base.board.tiles.map {
                if (it.hex == Hex(0, 0)) Tile(it.hex, TileType.FOREST, 5) else it.copy(number = 3)
            }
            return base.copy(
                board = base.board.copy(tiles = tiles, robber = Hex(-2, 2)),
                buildings = emptyMap(),
                roads = emptyMap(),
                bank = Resource.entries.associateWith { Rules.BANK_PER_RESOURCE },
                players = base.players.map { it.copy(resources = emptyResources()) },
            )
        }

        @Test
        fun `a settlement makes one card and a city makes two`() {
            val centre = Hex(0, 0)
            val state = riggedState().copy(
                buildings = mapOf(
                    VertexId.corner(centre, HexDirection.E) to
                        Building(BuildingType.SETTLEMENT, alice),
                    VertexId.corner(centre, HexDirection.NW) to
                        Building(BuildingType.CITY, bob),
                ),
            )

            val after = GameEngine.produce(state, 5)
            assertEquals(1, after.player(alice).resources[Resource.LUMBER])
            assertEquals(2, after.player(bob).resources[Resource.LUMBER])
            assertCardsConserved(after)
        }

        @Test
        fun `the robber stops a tile producing`() {
            val centre = Hex(0, 0)
            val state = riggedState().let {
                it.copy(
                    board = it.board.copy(robber = centre),
                    buildings = mapOf(
                        VertexId.corner(centre, HexDirection.E) to
                            Building(BuildingType.SETTLEMENT, alice),
                    ),
                )
            }
            val after = GameEngine.produce(state, 5)
            assertEquals(0, after.player(alice).resources[Resource.LUMBER])
        }

        @Test
        fun `a corner touching two matching tiles pays for each of them`() {
            // Both hexes flanking this corner are forests showing 5.
            val a = Hex(0, 0)
            val b = Hex(1, 0)
            val corner = VertexId.corner(a, HexDirection.E)
            assertTrue(corner.hexes.containsAll(listOf(a, b)))

            val base = riggedState()
            val tiles = base.board.tiles.map {
                if (it.hex == a || it.hex == b) Tile(it.hex, TileType.FOREST, 5) else it.copy(number = 3)
            }
            val state = base.copy(
                board = base.board.copy(tiles = tiles),
                buildings = mapOf(corner to Building(BuildingType.SETTLEMENT, alice)),
            )

            val after = GameEngine.produce(state, 5)
            assertEquals(2, after.player(alice).resources[Resource.LUMBER])
        }

        @Test
        fun `when the bank is short and several players are owed, nobody is paid`() {
            val centre = Hex(0, 0)
            val state = riggedState()
                .copy(
                    buildings = mapOf(
                        VertexId.corner(centre, HexDirection.E) to
                            Building(BuildingType.CITY, alice),
                        VertexId.corner(centre, HexDirection.NW) to
                            Building(BuildingType.CITY, bob),
                    ),
                )
                .let { it.copy(bank = it.bank.plusCards(mapOf(Resource.LUMBER to -(19 - 3)))) }

            assertEquals(3, state.bank[Resource.LUMBER]) // 3 available, 4 demanded
            val after = GameEngine.produce(state, 5)

            assertEquals(0, after.player(alice).resources[Resource.LUMBER])
            assertEquals(0, after.player(bob).resources[Resource.LUMBER])
            assertEquals(3, after.bank[Resource.LUMBER])
        }

        @Test
        fun `a lone claimant takes whatever the bank has left`() {
            val centre = Hex(0, 0)
            val state = riggedState()
                .copy(
                    buildings = mapOf(
                        VertexId.corner(centre, HexDirection.E) to
                            Building(BuildingType.CITY, alice),
                    ),
                )
                .let { it.copy(bank = it.bank.plusCards(mapOf(Resource.LUMBER to -(19 - 1)))) }

            assertEquals(1, state.bank[Resource.LUMBER]) // 1 available, 2 demanded
            val after = GameEngine.produce(state, 5)

            assertEquals(1, after.player(alice).resources[Resource.LUMBER])
            assertEquals(0, after.bank[Resource.LUMBER])
        }

        @Test
        fun `no tile ever shows a seven`() {
            val state = startedGame()
            val after = GameEngine.produce(state, 7)
            assertTrue(state.players.indices.all {
                after.players[it].resources == state.players[it].resources
            })
        }
    }

    // ------------------------------------------------------------------ the seven

    @Nested
    inner class RollingSeven {

        private fun stateOnSeven(): GameState {
            val state = startedGame()
                .giveExactly(
                    bob,
                    Resource.BRICK to 4,
                    Resource.LUMBER to 4,
                    Resource.WOOL to 1,
                )
                .giveExactly(cara, Resource.ORE to 3)
                .copy(phase = GamePhase.ROLL)
            return state.expectOk(alice, RollDice, ScriptedRandom(diceFor(7)))
        }

        @Test
        fun `players over seven cards must discard half, rounded down`() {
            val state = stateOnSeven()
            assertEquals(GamePhase.DISCARD, state.phase)
            assertEquals(setOf(bob), state.pendingDiscards) // Bob has 9, Cara has 3

            val wrongAmount = state.expectRejected(
                bob,
                Discard(mapOf(Resource.BRICK to 3)),
            )
            assertTrue(wrongAmount.contains("exactly 4"))

            val after = state.expectOk(
                bob,
                Discard(mapOf(Resource.BRICK to 4)),
            )
            assertEquals(5, after.player(bob).handSize)
            assertEquals(GamePhase.MOVE_ROBBER, after.phase)
            assertCardsConserved(after)
        }

        @Test
        fun `a player cannot discard cards they do not hold`() {
            val state = stateOnSeven()
            val reason = state.expectRejected(bob, Discard(mapOf(Resource.ORE to 4)))
            assertTrue(reason.contains("do not hold"))
        }

        @Test
        fun `exactly seven cards is safe`() {
            val state = startedGame()
                .giveExactly(bob, Resource.BRICK to 7)
                .copy(phase = GamePhase.ROLL)
                .expectOk(alice, RollDice, ScriptedRandom(diceFor(7)))
            assertEquals(GamePhase.MOVE_ROBBER, state.phase)
            assertTrue(state.pendingDiscards.isEmpty())
        }

        @Test
        fun `the robber must actually move`() {
            val state = stateOnSeven()
                .expectOk(bob, Discard(mapOf(Resource.BRICK to 4)))
            val reason = state.expectRejected(alice, MoveRobber(state.board.robber))
            assertTrue(reason.contains("different tile"))
        }

        @Test
        fun `moving the robber onto a lone victim steals one card`() {
            val base = startedGame()
            val bobsCorner = base.buildingsOf(bob).keys.first()
            val targetHex = bobsCorner.hexes.first { it in base.board.hexes }

            // Only Bob should be adjacent, so clear everyone else off that tile.
            val others = HexDirection.entries
                .map { VertexId.corner(targetHex, it) }
                .filter { base.buildings[it]?.owner?.let { o -> o != bob } == true }

            val state = base
                .copy(
                    buildings = base.buildings - others.toSet(),
                    phase = GamePhase.MOVE_ROBBER,
                    hasRolled = true,
                )
                .giveExactly(bob, Resource.ORE to 1)
                .giveExactly(alice)

            val after = state.expectOk(alice, MoveRobber(targetHex))
            assertEquals(1, after.player(alice).resources[Resource.ORE])
            assertEquals(0, after.player(bob).handSize)
            assertEquals(GamePhase.MAIN, after.phase)
            assertCardsConserved(after)
        }

        @Test
        fun `a knight before the roll returns to the roll phase`() {
            val state = startedGame()
                .updatePlayer(alice) {
                    it.copy(devCards = listOf(OwnedDevCard(DevCardType.KNIGHT, 0)))
                }
                .copy(phase = GamePhase.ROLL)

            val played = state.expectOk(alice, PlayKnight)
            assertEquals(GamePhase.MOVE_ROBBER, played.phase)

            val target = played.board.hexes.first { it != played.board.robber }
            val after = played.expectOk(alice, MoveRobber(target))
            assertEquals(GamePhase.ROLL, after.phase)
            assertFalse(after.hasRolled)
        }
    }

    // ------------------------------------------------------------------ dev cards

    @Nested
    inner class DevelopmentCards {

        @Test
        fun `the deck matches the printed distribution`() {
            assertEquals(25, Rules.DEV_DECK.size)
            val counts = Rules.DEV_DECK.groupingBy { it }.eachCount()
            assertEquals(14, counts[DevCardType.KNIGHT])
            assertEquals(5, counts[DevCardType.VICTORY_POINT])
            assertEquals(2, counts[DevCardType.ROAD_BUILDING])
            assertEquals(2, counts[DevCardType.MONOPOLY])
            assertEquals(2, counts[DevCardType.YEAR_OF_PLENTY])
        }

        @Test
        fun `a card cannot be played on the turn it was bought`() {
            val state = startedGame()
                .giveExactly(alice, Resource.WOOL to 1, Resource.GRAIN to 1, Resource.ORE to 1)
                .copy(phase = GamePhase.MAIN, hasRolled = true, devDeck = listOf(DevCardType.KNIGHT))
                .expectOk(alice, BuyDevCard)

            val reason = state.expectRejected(alice, PlayKnight)
            assertTrue(reason.contains("turn you bought it")) { reason }
        }

        @Test
        fun `a card bought last turn can be played`() {
            val bought = startedGame()
                .giveExactly(alice, Resource.WOOL to 1, Resource.GRAIN to 1, Resource.ORE to 1)
                .copy(phase = GamePhase.MAIN, hasRolled = true, devDeck = listOf(DevCardType.KNIGHT))
                .expectOk(alice, BuyDevCard)

            val nextTurn = bought.copy(turnNumber = bought.turnNumber + 1)
            val played = nextTurn.expectOk(alice, PlayKnight)
            assertEquals(1, played.player(alice).knightsPlayed)
        }

        @Test
        fun `only one development card may be played per turn`() {
            val state = startedGame()
                .updatePlayer(alice) {
                    it.copy(
                        devCards = listOf(
                            OwnedDevCard(DevCardType.KNIGHT, 0),
                            OwnedDevCard(DevCardType.MONOPOLY, 0),
                        ),
                    )
                }
                .copy(phase = GamePhase.MAIN, hasRolled = true)

            val afterKnight = state.expectOk(alice, PlayKnight)
            val resolved = afterKnight.expectOk(
                alice,
                MoveRobber(afterKnight.board.hexes.first { it != afterKnight.board.robber }),
            )
            val reason = resolved.expectRejected(alice, PlayMonopoly(Resource.ORE))
            assertTrue(reason.contains("one development card")) { reason }
        }

        @Test
        fun `monopoly takes every matching card from every opponent`() {
            val state = startedGame()
                .updatePlayer(alice) {
                    it.copy(devCards = listOf(OwnedDevCard(DevCardType.MONOPOLY, 0)))
                }
                .giveExactly(alice, Resource.ORE to 1)
                .giveExactly(bob, Resource.ORE to 3, Resource.WOOL to 2)
                .giveExactly(cara, Resource.ORE to 2)
                .copy(phase = GamePhase.MAIN, hasRolled = true)

            val after = state.expectOk(alice, PlayMonopoly(Resource.ORE))
            assertEquals(6, after.player(alice).resources[Resource.ORE])
            assertEquals(0, after.player(bob).resources[Resource.ORE])
            assertEquals(2, after.player(bob).resources[Resource.WOOL])
            assertEquals(0, after.player(cara).resources[Resource.ORE])
            assertCardsConserved(after)
        }

        @Test
        fun `year of plenty draws two cards from the bank`() {
            val state = startedGame()
                .updatePlayer(alice) {
                    it.copy(devCards = listOf(OwnedDevCard(DevCardType.YEAR_OF_PLENTY, 0)))
                }
                .giveExactly(alice)
                .copy(phase = GamePhase.MAIN, hasRolled = true)

            val after = state.expectOk(alice, PlayYearOfPlenty(Resource.ORE, Resource.ORE))
            assertEquals(2, after.player(alice).resources[Resource.ORE])
            assertCardsConserved(after)
        }

        @Test
        fun `road building gives exactly two free roads`() {
            val state = startedGame()
                .updatePlayer(alice) {
                    it.copy(devCards = listOf(OwnedDevCard(DevCardType.ROAD_BUILDING, 0)))
                }
                .giveExactly(alice)
                .copy(phase = GamePhase.MAIN, hasRolled = true)

            var after = state.expectOk(alice, PlayRoadBuilding)
            assertEquals(2, after.freeRoadsRemaining)

            // Other actions are locked out until both roads are down.
            assertTrue(after.expectRejected(alice, EndTurn).contains("free road"))

            repeat(2) {
                val spot = Placement.roadSpots(after, alice).sorted().first()
                after = after.expectOk(alice, BuildRoad(spot))
            }
            assertEquals(0, after.freeRoadsRemaining)
            assertEquals(0, after.player(alice).handSize) // never charged
            assertEquals(Rules.MAX_ROADS - 4, after.player(alice).roadsLeft)
            after.expectOk(alice, EndTurn)
        }

        @Test
        fun `largest army needs three knights and only moves on a strictly bigger army`() {
            var state = startedGame().copy(phase = GamePhase.MAIN, hasRolled = true)

            state = state.updatePlayer(alice) { it.copy(knightsPlayed = 2) }
            state = RoadLength.recalculateArmy(state)
            assertNull(state.largestArmyHolder)

            state = state.updatePlayer(alice) { it.copy(knightsPlayed = 3) }
            state = RoadLength.recalculateArmy(state)
            assertEquals(alice, state.largestArmyHolder)
            assertEquals(2, state.publicVictoryPoints(alice) - 2) // 2 settlements + army bonus

            // Bob ties at 3 - Alice keeps it.
            state = state.updatePlayer(bob) { it.copy(knightsPlayed = 3) }
            state = RoadLength.recalculateArmy(state)
            assertEquals(alice, state.largestArmyHolder)

            // Bob goes to 4 and takes it.
            state = state.updatePlayer(bob) { it.copy(knightsPlayed = 4) }
            state = RoadLength.recalculateArmy(state)
            assertEquals(bob, state.largestArmyHolder)
        }

        @Test
        fun `victory point cards count toward the total but stay off the public tally`() {
            val state = startedGame()
                .updatePlayer(alice) {
                    it.copy(devCards = listOf(OwnedDevCard(DevCardType.VICTORY_POINT, 0)))
                }
            assertEquals(state.publicVictoryPoints(alice) + 1, state.victoryPoints(alice))
        }

        @Test
        fun `buying from an empty deck is refused`() {
            val state = startedGame()
                .giveExactly(alice, Resource.WOOL to 1, Resource.GRAIN to 1, Resource.ORE to 1)
                .copy(phase = GamePhase.MAIN, hasRolled = true, devDeck = emptyList())
            assertTrue(state.expectRejected(alice, BuyDevCard).contains("empty"))
        }
    }

    // ------------------------------------------------------------------ longest road

    @Nested
    inner class LongestRoad {

        private val centre = Hex(0, 0)

        /** A state with a clean board so roads can be placed by hand. */
        private fun bare(): GameState =
            startedGame().copy(buildings = emptyMap(), roads = emptyMap())

        @Test
        fun `a straight run counts its segments`() {
            val roads = listOf(HexDirection.E, HexDirection.NE, HexDirection.NW)
                .associate { EdgeId.side(centre, it) to alice }
            val state = bare().copy(roads = roads)
            assertEquals(3, RoadLength.longestFor(state, alice))
        }

        @Test
        fun `a closed loop counts every segment`() {
            val roads = HexDirection.entries.associate { EdgeId.side(centre, it) to alice }
            val state = bare().copy(roads = roads)
            assertEquals(6, RoadLength.longestFor(state, alice))
        }

        @Test
        fun `an opponent's settlement cuts the road`() {
            val roads = listOf(HexDirection.E, HexDirection.NE, HexDirection.NW)
                .associate { EdgeId.side(centre, it) to alice }
            val cut = VertexId.corner(centre, HexDirection.E) // between the E and NE segments

            val state = bare().copy(
                roads = roads,
                buildings = mapOf(cut to Building(BuildingType.SETTLEMENT, bob)),
            )
            assertEquals(2, RoadLength.longestFor(state, alice))
        }

        @Test
        fun `your own settlement does not cut your road`() {
            val roads = listOf(HexDirection.E, HexDirection.NE, HexDirection.NW)
                .associate { EdgeId.side(centre, it) to alice }
            val corner = VertexId.corner(centre, HexDirection.E)

            val state = bare().copy(
                roads = roads,
                buildings = mapOf(corner to Building(BuildingType.SETTLEMENT, alice)),
            )
            assertEquals(3, RoadLength.longestFor(state, alice))
        }

        @Test
        fun `a branch does not double-count`() {
            // Three segments meeting at one corner: the best route uses two of them.
            val hub = VertexId.corner(centre, HexDirection.E)
            val spokes = hub.incidentEdges().associateWith { alice }
            val state = bare().copy(roads = spokes.mapKeys { it.key })
            assertEquals(3, state.roadsOf(alice).size)
            assertEquals(2, RoadLength.longestFor(state, alice))
        }

        @Test
        fun `the card needs five segments`() {
            var state = bare().copy(
                roads = listOf(
                    HexDirection.E,
                    HexDirection.NE,
                    HexDirection.NW,
                    HexDirection.W,
                ).associate { EdgeId.side(centre, it) to alice },
            )
            state = RoadLength.recalculate(state)
            assertNull(state.longestRoadHolder)

            state = state.copy(roads = state.roads + (EdgeId.side(centre, HexDirection.SW) to alice))
            state = RoadLength.recalculate(state)
            assertEquals(alice, state.longestRoadHolder)
            assertEquals(5, state.longestRoadLength)
        }

        @Test
        fun `a tie leaves the card with the current holder`() {
            val aliceRoads = HexDirection.entries.take(5)
                .associate { EdgeId.side(centre, it) to alice }
            val far = Hex(-2, 2)
            val bobRoads = HexDirection.entries.take(5)
                .associate { EdgeId.side(far, it) to bob }

            var state = bare().copy(roads = aliceRoads)
            state = RoadLength.recalculate(state)
            assertEquals(alice, state.longestRoadHolder)

            state = RoadLength.recalculate(state.copy(roads = aliceRoads + bobRoads))
            assertEquals(alice, state.longestRoadHolder) { "a tie should not move the card" }
        }

        @Test
        fun `breaking the holder's road into a tie sets the card aside`() {
            val far = Hex(-2, 2)
            val aliceRoads = HexDirection.entries.take(6)
                .associate { EdgeId.side(centre, it) to alice }
            val bobRoads = HexDirection.entries.take(5)
                .associate { EdgeId.side(far, it) to bob }
            val caraRoads = HexDirection.entries.take(5)
                .associate { EdgeId.side(Hex(2, -2), it) to cara }

            var state = bare().copy(roads = aliceRoads + bobRoads + caraRoads)
            state = RoadLength.recalculate(state)
            assertEquals(alice, state.longestRoadHolder)
            assertEquals(6, state.longestRoadLength)

            // Cut Alice's loop so she drops to 5... actually to 3, leaving Bob and Cara tied at 5.
            val cut = VertexId.corner(centre, HexDirection.E)
            val cut2 = VertexId.corner(centre, HexDirection.W)
            state = RoadLength.recalculate(
                state.copy(
                    buildings = mapOf(
                        cut to Building(BuildingType.SETTLEMENT, bob),
                        cut2 to Building(BuildingType.SETTLEMENT, cara),
                    ),
                ),
            )
            assertTrue(RoadLength.longestFor(state, alice) < 5)
            assertNull(state.longestRoadHolder) { "a tie between two others should set it aside" }
        }

        @Test
        fun `building a settlement recomputes longest road immediately`() {
            var state = bare().copy(
                roads = HexDirection.entries.take(5)
                    .associate { EdgeId.side(centre, it) to alice },
            )
            state = RoadLength.recalculate(state)
            assertEquals(alice, state.longestRoadHolder)

            // Bob builds on the corner that splits it.
            val cut = VertexId.corner(centre, HexDirection.NE)
            val bobRoad = EdgeId.side(centre, HexDirection.NE)
            state = state.copy(
                roads = state.roads + (bobRoad to bob),
                phase = GamePhase.MAIN,
                hasRolled = true,
                currentPlayerIndex = 1,
            )
            // place Bob's settlement through the engine so recalculation is exercised
            val withRoads = state.copy(
                roads = state.roads.filterKeys { it != bobRoad } + (bobRoad to bob),
            )
            val placed = RoadLength.recalculate(
                withRoads.copy(
                    buildings = withRoads.buildings + (cut to Building(BuildingType.SETTLEMENT, bob)),
                ),
            )
            assertTrue(RoadLength.longestFor(placed, alice) < 5)
            assertNull(placed.longestRoadHolder)
        }
    }

    // ------------------------------------------------------------------ trading

    @Nested
    inner class Trading {

        @Test
        fun `the default bank rate is four to one`() {
            val state = startedGame()
                .giveExactly(alice, Resource.ORE to 4)
                .copy(phase = GamePhase.MAIN, hasRolled = true, board = noHarborBoard())

            val after = state.expectOk(alice, BankTrade(Resource.ORE, Resource.WOOL))
            assertEquals(0, after.player(alice).resources[Resource.ORE])
            assertEquals(1, after.player(alice).resources[Resource.WOOL])
            assertCardsConserved(after)
        }

        @Test
        fun `three of a kind is not enough without a harbor`() {
            val state = startedGame()
                .giveExactly(alice, Resource.ORE to 3)
                .copy(phase = GamePhase.MAIN, hasRolled = true, board = noHarborBoard())
            assertTrue(state.expectRejected(alice, BankTrade(Resource.ORE, Resource.WOOL))
                .contains("need 4"))
        }

        @Test
        fun `a generic harbor gives three to one`() {
            val base = startedGame()
            val corner = base.buildingsOf(alice).keys.first()
            val edge = base.board.edgesAt(corner).first()

            val state = base
                .copy(board = base.board.copy(harbors = listOf(Harbor(edge, null))))
                .giveExactly(alice, Resource.ORE to 3)
                .copy(phase = GamePhase.MAIN, hasRolled = true)

            assertEquals(3, state.tradeRatio(alice, Resource.ORE))
            val after = state.expectOk(alice, BankTrade(Resource.ORE, Resource.WOOL))
            assertEquals(0, after.player(alice).resources[Resource.ORE])
            assertEquals(1, after.player(alice).resources[Resource.WOOL])
        }

        @Test
        fun `a matching harbor gives two to one and only for that resource`() {
            val base = startedGame()
            val corner = base.buildingsOf(alice).keys.first()
            val edge = base.board.edgesAt(corner).first()

            val state = base
                .copy(board = base.board.copy(harbors = listOf(Harbor(edge, Resource.ORE))))
                .giveExactly(alice, Resource.ORE to 2, Resource.WOOL to 2)
                .copy(phase = GamePhase.MAIN, hasRolled = true)

            assertEquals(2, state.tradeRatio(alice, Resource.ORE))
            assertEquals(4, state.tradeRatio(alice, Resource.WOOL))

            val after = state.expectOk(alice, BankTrade(Resource.ORE, Resource.GRAIN))
            assertEquals(0, after.player(alice).resources[Resource.ORE])
            assertTrue(after.expectRejected(alice, BankTrade(Resource.WOOL, Resource.GRAIN))
                .contains("need 4"))
        }

        @Test
        fun `a harbor is only usable once you build on it`() {
            val base = startedGame()
            val unbuilt = base.board.vertices.first { base.buildings[it] == null }
            val edge = base.board.edgesAt(unbuilt).first()
            val state = base.copy(board = base.board.copy(harbors = listOf(Harbor(edge, Resource.ORE))))
            assertEquals(4, state.tradeRatio(alice, Resource.ORE))
        }

        @Test
        fun `a player trade moves cards both ways`() {
            var state = startedGame()
                .giveExactly(alice, Resource.ORE to 2)
                .giveExactly(bob, Resource.WOOL to 1)
                .copy(phase = GamePhase.MAIN, hasRolled = true)

            state = state.expectOk(
                alice,
                OfferTrade(mapOf(Resource.ORE to 2), mapOf(Resource.WOOL to 1)),
            )
            assertNotNull(state.openTrade)

            state = state.expectOk(bob, RespondToTrade(true))
            assertEquals(setOf(bob), state.openTrade?.acceptedBy)

            state = state.expectOk(alice, ConfirmTrade(bob))
            assertNull(state.openTrade)
            assertEquals(0, state.player(alice).resources[Resource.ORE])
            assertEquals(1, state.player(alice).resources[Resource.WOOL])
            assertEquals(2, state.player(bob).resources[Resource.ORE])
            assertCardsConserved(state)
        }

        @Test
        fun `a player who cannot pay may not accept`() {
            val state = startedGame()
                .giveExactly(alice, Resource.ORE to 2)
                .giveExactly(bob)
                .copy(phase = GamePhase.MAIN, hasRolled = true)
                .expectOk(alice, OfferTrade(mapOf(Resource.ORE to 2), mapOf(Resource.WOOL to 1)))

            assertTrue(state.expectRejected(bob, RespondToTrade(true)).contains("do not hold"))
        }

        @Test
        fun `you cannot offer cards you do not have`() {
            val state = startedGame()
                .giveExactly(alice, Resource.ORE to 1)
                .copy(phase = GamePhase.MAIN, hasRolled = true)
            assertTrue(
                state.expectRejected(
                    alice,
                    OfferTrade(mapOf(Resource.ORE to 5), mapOf(Resource.WOOL to 1)),
                ).contains("do not hold"),
            )
        }

        @Test
        fun `you cannot trade a resource for itself`() {
            val state = startedGame()
                .giveExactly(alice, Resource.ORE to 5)
                .copy(phase = GamePhase.MAIN, hasRolled = true)
            assertTrue(
                state.expectRejected(
                    alice,
                    OfferTrade(mapOf(Resource.ORE to 2), mapOf(Resource.ORE to 1)),
                ).contains("same resource"),
            )
            assertTrue(
                state.expectRejected(alice, BankTrade(Resource.ORE, Resource.ORE))
                    .contains("different"),
            )
        }

        private fun noHarborBoard() = startedGame().board.copy(harbors = emptyList())
    }

    // ------------------------------------------------------------------ turn flow

    @Nested
    inner class TurnFlow {

        @Test
        fun `you must roll before building`() {
            val state = startedGame()
                .giveExactly(alice, Resource.BRICK to 1, Resource.LUMBER to 1)
            assertEquals(GamePhase.ROLL, state.phase)
            val spot = Placement.roadSpots(state, alice).sorted().first()
            assertTrue(state.expectRejected(alice, BuildRoad(spot)).contains("cannot do that"))
        }

        @Test
        fun `you cannot roll twice`() {
            val state = startedGame().expectOk(alice, RollDice, ScriptedRandom(diceFor(4)))
            assertEquals(GamePhase.MAIN, state.phase)
            assertTrue(state.expectRejected(alice, RollDice).contains("cannot do that"))
        }

        @Test
        fun `ending a turn passes the seat and resets the per-turn flags`() {
            var state = startedGame()
                .expectOk(alice, RollDice, ScriptedRandom(diceFor(4)))
                .updatePlayer(alice) { it.copy(playedDevCardThisTurn = true) }

            state = state.expectOk(alice, EndTurn)
            assertEquals(bob, state.currentPlayer.id)
            assertEquals(GamePhase.ROLL, state.phase)
            assertFalse(state.hasRolled)
            assertNull(state.lastRoll)
            assertFalse(state.player(alice).playedDevCardThisTurn)
            assertEquals(2, state.turnNumber)
        }

        @Test
        fun `the seat wraps around back to the first player`() {
            var state = startedGame(3)
            repeat(3) {
                state = state.expectOk(state.currentPlayer.id, RollDice, ScriptedRandom(diceFor(4)))
                state = state.expectOk(state.currentPlayer.id, EndTurn)
            }
            assertEquals(alice, state.currentPlayer.id)
        }

        @Test
        fun `another player cannot end your turn`() {
            val state = startedGame().expectOk(alice, RollDice, ScriptedRandom(diceFor(4)))
            assertTrue(state.expectRejected(bob, EndTurn).contains("not your turn"))
        }
    }

    // ------------------------------------------------------------------ victory

    @Nested
    inner class Victory {

        @Test
        fun `ten points ends the game`() {
            val state = startedGame()
                .updatePlayer(alice) {
                    it.copy(devCards = List(5) { OwnedDevCard(DevCardType.VICTORY_POINT, 0) })
                }
                .giveExactly(alice, Resource.GRAIN to 2, Resource.ORE to 3)
                .copy(phase = GamePhase.MAIN, hasRolled = true)

            // 2 settlements + 5 card points = 7; upgrading one settlement adds 1 -> 8.
            assertEquals(7, state.victoryPoints(alice))

            val nearlyThere = state.updatePlayer(alice) { it.copy(knightsPlayed = 3) }
                .let { RoadLength.recalculateArmy(it) }
            assertEquals(9, nearlyThere.victoryPoints(alice))
            assertNull(nearlyThere.winner)

            val own = nearlyThere.buildingsOf(alice).keys.first()
            val after = nearlyThere.expectOk(alice, BuildCity(own))
            assertEquals(10, after.victoryPoints(alice))
            assertEquals(alice, after.winner)
            assertEquals(GamePhase.GAME_OVER, after.phase)
        }

        @Test
        fun `nothing may happen once the game is over`() {
            val over = startedGame().copy(phase = GamePhase.GAME_OVER, winner = alice)
            assertTrue(over.expectRejected(alice, RollDice).contains("game is over"))
        }

        @Test
        fun `nine points is not a win`() {
            val state = startedGame()
                .updatePlayer(alice) {
                    it.copy(devCards = List(5) { OwnedDevCard(DevCardType.VICTORY_POINT, 0) })
                }
                .copy(phase = GamePhase.MAIN, hasRolled = true)
                .updatePlayer(alice) { it.copy(knightsPlayed = 3) }
                .let { RoadLength.recalculateArmy(it) }

            assertEquals(9, state.victoryPoints(alice))
            val after = state.expectOk(alice, EndTurn)
            assertNull(after.winner)
        }
    }
}
