# Catan — Android multiplayer

An online multiplayer Catan for Android, playing by the base-game rules for 2–4 players.

## Layout

| Module    | What it is | Runs on |
|-----------|------------|---------|
| `core`    | The rules engine, board generation and wire protocol. Pure Kotlin, no Android. | JVM + Android |
| `server`  | The authoritative game server. Ktor + WebSockets. | JVM |
| `app`     | The Android client. Kotlin + Jetpack Compose. | Android |

Every rule lives in `core`, and both the server and the app use it. The server decides what
actually happens; the app uses the same code only to grey out moves it knows are illegal, so the
two can never disagree about the rules.

## Firebase Cloud Multiplayer

The game uses **Firebase Realtime Database** for cloud multiplayer — no local server or port forwarding required!

### 1. Firebase Setup

1. Create a Firebase project in the [Firebase Console](https://console.firebase.google.com/).
2. Add an Android app with package name `com.catan.app`.
3. Download `google-services.json` and place it in the `app/` directory of this project.
4. Enable **Firebase Realtime Database** in test mode or with read/write rules enabled for `/rooms`.

### 2. Run the App

1. Open the project in Android Studio and run the `app` configuration on your device or emulator.
2. Tap **Host a new game** to generate a 4-character Room Code.
3. Share the Room Code with up to 3 other players. They enter the Room Code and tap **Join**.
4. Tap **Start Game** once players have joined!


## Rules

The base game for 2–4 players, as printed:

- **Setup** in snake order; the second settlement pays out its surrounding tiles.
- **Board** of 19 tiles with the standard resource mix, the 18 number tokens, and no two red
  numbers (6 and 8) on touching tiles.
- **Nine harbours** — four generic 3:1 and one 2:1 for each resource — usable once you build on one.
- **Production** on every roll, doubled for cities, blocked by the robber. If the bank cannot cover
  what a resource owes and more than one player is due some, nobody receives any of it.
- **On a seven**, everyone over seven cards discards half, rounded down, then the robber moves and
  steals.
- **Building** costs and piece limits as printed, including the distance rule and the rule that a
  road cannot be routed through an opponent's settlement.
- **25 development cards** (14 knights, 5 victory points, 2 each of the rest). One per turn, and
  never on the turn it was bought.
- **Longest Road** at five or more, held while tied, and set aside if the holder is overtaken by
  two players at once. Roads are cut by opponent buildings.
- **Largest Army** at three knights, moving only on a strictly larger army.
- **Ten points on your own turn** wins.

Hidden information stays hidden: the server strips other players' hands, their development cards
and the undrawn deck from every update before sending it.

## Missing artwork

The asset set has no harbours and no dice, so both are drawn in code — harbours as jetties and a
dock with the trade rate in `BoardView.kt`, dice as pips in `Dice.kt`. They scale to any screen and
need no image files.

## Tests

```bash
./gradlew test
```

90 tests covering:

- board invariants — 54 corners, 72 edges, a closed 30-edge coastline, nine harbours that never
  share a corner, red numbers never adjacent;
- the rules, case by case, including the ones that are easy to get wrong: the bank shortage rule,
  roads cut by opponent buildings, Longest Road ties, development card timing;
- **120 complete simulated games**, checking after *every single action* that cards are conserved,
  piece supplies match the board, and the distance rule holds;
- screen layout maths — tiles tessellate, roads sit at the three hex angles, harbours point out to
  sea;
- **a full four-client game over real WebSockets** against a running server, verifying that no
  client is ever sent another player's hand, plus reconnection.

## Notes

- `core` and `server` build without an Android SDK; `app` is only included when one is present.
- A dropped player keeps their seat and reconnects automatically.
