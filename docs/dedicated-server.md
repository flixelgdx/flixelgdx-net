# 4. Dedicated servers

## Why

When a player hosts, the game lives on their computer: if they quit, everyone is dropped, and joining them over
the internet needs router setup. A **dedicated server** is the same game running on a computer nobody plays on,
usually a small rented one with a public address. It is always on, anyone can reach it, and no player has an
advantage for being the host.

A normal game is like a cinema: it opens a screen and shows a frame at a time. A dedicated server is the same
projector with the lamp switched off. Your states and objects update exactly as they would on a player's
computer, and nothing is drawn.

## The launcher

Add a `server` module to your game next to `desktop`:

```kotlin
// server/build.gradle.kts
plugins {
  application
}

dependencies {
  implementation(project(":core"))
  implementation("org.flixelgdx:flixelgdx-net-server:$netVersion")
  // Optional: lets browser players use WebRTC with this server.
  runtimeOnly("org.flixelgdx:flixelgdx-net-natives-linux-x86_64:$netVersion")
}

application {
  mainClass = "com.example.mygame.server.ServerLauncher"
}
```

```java
public class ServerLauncher {
  public static void main(String[] args) {
    FlixelServerLauncher.launch(new MyGame());
  }
}
```

That is all. `launch` installs the network transports, turns off pausing, and runs the game at 60 updates a
second until the process is stopped. Notice the module does not depend on `flixelgdx-desktop`: a server needs
no graphics card and no display.

## Telling the game it is a server

Your game decides what to do differently. Usually: skip the menu and start hosting. The server module is not
visible from your shared `core` code, so let the launcher pass the fact in, for example through a field on your
game class:

```java
// core
public class MyGame extends FlixelGame {
  public static boolean dedicated;

  public MyGame() {
    super("My Game", 1280, 720, () -> dedicated ? new ArenaState() : new MenuState());
  }
}

// server
public static void main(String[] args) {
  MyGame.dedicated = true;
  FlixelServerLauncher.launch(new MyGame());
}
```

Inside the server:

- **Do not join as a player.** There is nobody at the keyboard, so skip `joinLocal` and the local player.
- **Graphics, sound and input do nothing.** A state that loads a texture or plays a sound does not crash; the
  call just has no effect. Still, skip work that only matters on screen.
- **Log what happens.** With no window, `Flixel.info(...)` is how you see players come and go.

## Running it

```bash
./gradlew :server:run
```

On a rented Linux computer, build a distribution with `./gradlew :server:installDist`, copy
`server/build/install/server` over, and start `bin/server`. Open the game's port in the server's firewall for
**UDP and TCP**.

Stopping the process the usual way (Ctrl+C, `docker stop`, or a service manager's stop command) shuts the game
down cleanly: the current update finishes and the game is destroyed, so players are told the session ended
instead of waiting for a timeout. From inside the game, call `FlixelServerLauncher.stop()`.

## The update rate

```java
FlixelServerLauncher.launch(new MyGame(), 30);   // 30 updates a second.
```

Use the rate your game runs at for players unless you have measured that you need otherwise. A lower rate uses
less processor time on a rented computer. If you use replication, the world's tick rate is separate and keeps
its own steady pace whatever the server's update rate is.

## Browser players and a dedicated server

Browsers on an `https://` page can only open encrypted connections. See
[Browser players](hosting-and-joining.md#browser-players) for the small reverse proxy that provides one. A
dedicated server with a public address needs no STUN or TURN servers for WebRTC.

Next: [Rollback](rollback.md)
