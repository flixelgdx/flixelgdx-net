# 4. Dedicated servers

On this page you run the replicated arena from [Replication](replication.md) with no window, first on your own
computer and then on a rented server on the internet, so anyone can join at any time.

## Why

When a player hosts, the game lives on their computer. If they quit, everyone is dropped, and friends across the
internet can only join if the host sets up their router. A **dedicated server** is the same game running on a
computer nobody plays on, usually a small rented one with a public address. It is always on, anyone can reach
it, and no player gets an advantage for being the host.

A normal game is like a cinema projector showing one frame after another. A dedicated server is the same
projector with the lamp switched off: your states and objects update exactly as they would on a player's
computer, and nothing is drawn.

## Step 1: a state for the server

A server is just the host's half of the game, with no local player and nothing to draw. Because the arena keeps
drawing out of its entities, the server can reuse the same `Square` and `Ball` classes and the same `newWorld`
setup:

```java
public class ServerState extends FlixelState {

  private FlixelNetSession session;
  private FlixelNetWorld world;

  @Override
  public void create() {
    super.create();
    session = new FlixelNetSession(Messages.protocol());
    world = ArenaState.newWorld(session);

    session.onConnect.add(peer -> {
      Flixel.info("Player " + peer.getId() + " joined over " + peer.getRoute());
      Square square = world.spawn(ArenaState.SQUARE, peer.getId());
      square.x = 100 + peer.getId() * 60;
      square.y = 100;
    });
    session.onDisconnect.add(peer -> {
      Flixel.info("Player " + peer.getId() + " left (" + peer.getDisconnectReason() + ")");
      world.despawnOwnedBy(peer.getId());
    });

    FlixelNet.host(session, 7777);
    world.spawn(ArenaState.BALL, FlixelNetSession.HOST_ID);
    Flixel.info("Server ready on port 7777");
  }

  @Override
  public void update(float elapsed) {
    super.update(elapsed);
    session.update(elapsed);
    world.update(elapsed);
  }

  @Override
  public void destroy() {
    world.destroy();
    session.close();   // Tells every player the server is shutting down.
    super.destroy();
  }
}
```

With no window, `Flixel.info(...)` is how you see what the server is doing: it prints to the terminal.

## Step 2: let the game start on it

The server launcher lives in its own module, which your shared `core` code cannot see. So the launcher tells the
game which state to open, the same way the desktop launcher passes `--args` in
[Getting started](getting-started.md#step-3-keep-the-game-running-in-the-background):

```java
public class SquaresGame extends FlixelGame {

  public SquaresGame(String start) {
    super(new FlixelConfig.Builder("Squares").size(1280, 720).build(), () -> first(start));
    Flixel.autoPause = false;
  }

  private static FlixelState first(String start) {
    if ("server".equals(start)) {
      return new ServerState();
    }
    if ("host".equals(start)) {
      return new ArenaState(true, "");
    }
    if (start != null && start.startsWith("join:")) {
      return new ArenaState(false, start.substring(5));
    }
    return new ArenaState(true, "");   // Anything else: host and play.
  }
}
```

## Step 3: the server module

Add a `server` module to your game, next to `desktop`. Include it in `settings.gradle.kts`:

```kotlin
include("core", "desktop", "server")
```

```kotlin
// server/build.gradle.kts
plugins {
  application
}

java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(25)
  }
}

application {
  mainClass = "com.example.squares.server.ServerLauncher"
  // The WebRTC library is native code, which Java only loads quietly when this is allowed.
  applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

dependencies {
  implementation(project(":core"))
  implementation("org.flixelgdx:flixelgdx-net-server:0.0.0")
  // Optional: lets browser players use WebRTC with this server. Add the platform the server runs on.
  runtimeOnly("org.flixelgdx:flixelgdx-net-natives-linux-x86_64:0.0.0")
}
```

```java
package com.example.squares.server;

public final class ServerLauncher {

  public static void main(String[] args) {
    FlixelServerLauncher.launch(new SquaresGame("server"));
  }
}
```

That is all the code. `launch` installs the network transports, turns off pausing, and runs the game 60 times a
second until the process is stopped.

Notice the module does **not** depend on `flixelgdx-desktop`. A server needs no graphics card, no display and no
sound. Graphics, sound and input calls do nothing on a server: a state that loads a texture or plays a sound
does not crash, the call just has no effect.

## Step 4: try it on your own computer

```bash
./gradlew :server:run
```

The terminal prints `Server ready on port 7777`. Start the desktop game with
`./gradlew :desktop:run --args=join:127.0.0.1:7777` (as many times as you like) and play. The server prints each
player arriving and leaving. Press Ctrl+C to stop it.

## Step 5: put it on the internet

You need a computer with a public address that stays on. The usual choice is a small rented Linux server, often
called a **VPS** (virtual private server), from any hosting company. A small one is enough to start; a server
that only moves a few squares uses very little.

The commands below assume Ubuntu or Debian on the server, a user named `you`, and an address of `203.0.113.10`.
Use your own.

### 1. Install Java on the server

The server needs a Java 25 runtime. Install [Eclipse Temurin](https://adoptium.net/installation/linux/) 25 by
following its Linux instructions, then check:

```bash
java -version
```

### 2. Build and copy the server

On your own computer, in the game's folder:

```bash
./gradlew :server:installDist
```

This makes `server/build/install/server`: a folder with your game, every library it needs, and a start script.
Copy it to the server:

```bash
scp -r server/build/install/server you@203.0.113.10:~/squares
```

### 3. Open the port

Open port 7777 for **both UDP and TCP** in the server's firewall:

```bash
sudo ufw allow 7777/udp
sudo ufw allow 7777/tcp
```

Many hosting companies also have a firewall in their website's control panel. Open the same port there.

### 4. Start it

```bash
~/squares/bin/server
```

Players can now join `203.0.113.10:7777`. Press Ctrl+C to stop it.

### 5. Keep it running

A server started from a terminal stops when you log out. To keep it running, and to restart it after a crash or
a reboot, make it a **service**. Create `/etc/systemd/system/squares.service`:

```ini
[Unit]
Description=Squares game server
After=network-online.target

[Service]
User=you
ExecStart=/home/you/squares/bin/server
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

Then:

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now squares    # Start now, and on every boot.
journalctl -u squares -f               # Watch its log. Ctrl+C stops watching, not the server.
```

To update the game later: build and copy again, then `sudo systemctl restart squares`.

Stopping the server the usual way (Ctrl+C, `systemctl stop`, or `docker stop`) shuts the game down cleanly: the
current update finishes and the game is destroyed, so players are told the session ended instead of waiting for
a timeout. From inside the game, call `FlixelServerLauncher.stop()`.

## Browser players

Players on desktop and Android join `203.0.113.10:7777` directly. Browser players need two more things, because
your game's web page will be served over `https://`, and such a page may only open encrypted `wss://`
connections:

1. **A domain name** pointing at the server, such as `play.example.com`. Buy one from any domain seller and add
   an "A record" with the server's address.
2. **A reverse proxy** that holds the certificate and passes traffic on to the game. With
   [Caddy](https://caddyserver.com/docs/install), which gets free certificates by itself, put this in
   `/etc/caddy/Caddyfile`:

   ```
   play.example.com {
     reverse_proxy localhost:7777
   }
   ```

   Then open the web ports and reload Caddy:

   ```bash
   sudo ufw allow 80/tcp
   sudo ufw allow 443/tcp
   sudo systemctl reload caddy
   ```

Browser players then join `wss://play.example.com`. Once connected, they upgrade to WebRTC directly with the
server on port 7777, because a server with a public address needs no STUN or TURN servers.

## The update rate

```java
FlixelServerLauncher.launch(new SquaresGame("server"), 30);   // 30 updates a second.
```

The default is 60. A lower rate uses less of the rented computer's processor. If you use replication, the
world's tick rate is separate and keeps its own steady pace whatever the server's update rate is, so lowering the
server rate mostly makes the server answer a little less often.

## Limits of the current design

- **One game per process.** One server process runs one session on one port. To run several matches, start
  several processes on different ports.
- **No server list or matchmaking.** Players type the address. A list of servers would be a small web service of
  your own.
- **No accounts.** Anyone who knows the address can join. Use `session.setMaxPeers(...)` and `session.kick(...)`
  to manage who stays.

Next: [Rollback](rollback.md)
