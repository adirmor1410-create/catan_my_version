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

## Running it

### 1. Start the server

```bash
./gradlew :server:run
```

It listens on port 8080. Set `PORT` to change that. To run it somewhere other than your own
machine, build a self-contained distribution:

```bash
./gradlew :server:installDist
./server/build/install/server/bin/server
```

The server keeps games in memory, so restarting it ends any game in progress.

### 2. Run the app

Open the project in Android Studio and run the `app` configuration.

On the **connect screen**, enter the server address:

| Where the app runs | What to enter |
|---|---|
| Android emulator, server on the same machine | `10.0.2.2:8080` (the default) |
| Real phone, server on your computer | your computer's LAN address, e.g. `192.168.1.20:8080` |
| Anyone, anywhere | a public address - see below |

One player taps **Host a new game** and reads out the four-letter room code; everyone else enters
that code and taps **Join**. When at least two players are in, the host starts the game.

The app talks plain `ws://` by default, which is why the manifest allows cleartext traffic. Put the
server behind TLS and use a `wss://` address for anything beyond your own network.

### 3. Playing with people on a different network

The room code identifies a room *on one server*. Everyone must reach the same server, so a server
running on a home PC has to be reachable from the internet. Two ways:

**A tunnel** - quickest, nothing to deploy. Leave `:server:run` going and, in another terminal:

```bash
cloudflared tunnel --url http://localhost:8080
```

It prints a public `https://<something>.trycloudflare.com` address. Everyone types that into the
app, on any network. Paste it as-is: an address with a scheme keeps its own port (443), so don't
add `:8080`. The address changes each time you restart the tunnel. `ngrok http 8080` works the
same way.

**Hosting the server** - for an address that stays put. There is a `Dockerfile` that builds only
`:core` and `:server`, so no Android SDK is involved:

```bash
docker build -t catan-server .
docker run -p 8080:8080 catan-server
```

It runs as-is on any host that takes a Dockerfile (Render, Railway, Fly.io, or your own VPS).
The server binds `0.0.0.0` and reads `$PORT`, which is what those platforms set. Players then use
`wss://your-app.onrender.com`, and `/health` answers `ok` for health checks.

Rooms are held in memory, so restarting or redeploying the server ends any game in progress.

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
