# 2. Hosting and joining

## Three roads, chosen for you

Different kinds of players reach a host in different ways. You do not pick; `FlixelNet.host` opens all of them
on one port number, and `FlixelNet.join` takes the best one the platform has.

| Road | Used by | Why |
| --- | --- | --- |
| **UDP** | Desktop and Android players | The fastest: nothing between your message and the wire. |
| **WebSocket** | Browser players, at first | The only kind of connection a web page can always open. Everything on it is reliable, so one lost packet makes the ones behind it wait. |
| **WebRTC** | Browser players, after a moment | Lets a browser send unreliable messages too, so positions never wait in a queue. |

A browser player starts on the WebSocket and can play right away. In the background, the two sides negotiate a
WebRTC connection over that WebSocket; when it is ready, traffic moves over without the game noticing. If WebRTC
cannot connect (a strict firewall, for example), the player simply stays on the WebSocket.

`peer.getRoute()` tells you which road a peer is on: `"UDP"`, `"WebSocket"`, `"WebRTC"` or `"Loopback"`. Showing
it on a debug screen is handy.

## Addresses

```java
FlixelNet.join(session, "192.168.1.20:7777");        // Desktop and Android: UDP. Browser: WebSocket.
FlixelNet.join(session, "ws://192.168.1.20:7777");   // Join the way a browser does, from any platform.
FlixelNet.join(session, "wss://play.example.com");   // The same, over an encrypted connection.
```

## Playing on one local network

Players on the same Wi-Fi or wired network can reach each other with no setup.

1. The host starts hosting.
2. The host looks up their computer's local address (it usually starts with `192.168.` or `10.`).
3. Everyone else joins `thataddress:7777`.

If joining times out, the host's firewall is almost always the reason. The host needs to allow the game through
it for both **UDP and TCP** on the port (UDP for desktop players, TCP for browser players).

## Playing over the internet

A home router hides the computers behind it, like an office where every desk shares one street address. A letter
addressed to the building arrives, but the mail room does not know which desk it is for. There are three ways
to deal with that, from simplest to most robust:

1. **Port forwarding.** The host tells their router "anything for port 7777 goes to my computer" (UDP and TCP).
   Players then join the host's public address. Free, but every host has to do it, and some internet providers
   make it impossible.
2. **A dedicated server.** Rent a small computer with a public address and run the game there
   ([Dedicated servers](dedicated-server.md)). Nobody has to configure anything, and it is the usual choice for
   a released game.
3. **A relay** (TURN, below), for WebRTC connections that cannot find a direct path.

## Browser players

Browsers add two rules of their own.

**A browser cannot host.** Web pages are not allowed to accept connections. A browser player can only join a
desktop, Android or dedicated host.

**A secure page needs a secure connection.** If your game's page is served over `https://` (every real website
is), the browser refuses to open a plain `ws://` connection to anything but `localhost`. It must be `wss://`,
which needs a certificate. The host itself listens with plain `ws://`, so put a *reverse proxy* in front of it:
a small web server that owns the certificate and passes the traffic on. With [Caddy](https://caddyserver.com),
which fetches certificates by itself, the whole configuration is:

```
play.example.com {
  reverse_proxy localhost:7777
}
```

Browser players then join `wss://play.example.com`. Desktop players can keep using `play.example.com:7777`
directly. While developing, none of this matters: a page served from `http://localhost` may use `ws://`.

## STUN and TURN

WebRTC wants a *direct* path between the browser and the host. To find one through routers, it can ask helper
servers. You only need them when browser players and the host are on different networks and the host has no
public address.

- A **STUN** server is a mirror. A computer asks it "what address do you see me as?", learns its public address,
  and tells the other side. STUN servers are light, and free public ones exist.
- A **TURN** server is a go-between. When no direct path exists at all, both sides send to the TURN server and
  it passes every packet along. That costs its owner bandwidth, so it needs a user name and password, and you
  either rent one or run one yourself ([coturn](https://github.com/coturn/coturn) is the usual choice).

```java
// Before hosting or joining, on the host and on the clients:
FlixelNet.getIce().add("stun:stun.example.com:3478");
FlixelNet.getIce().add("turn:turn.example.com:3478", "user", "password");
```

| Situation | What you need |
| --- | --- |
| Everyone on one local network | Nothing. |
| Host has a public address (a rented server, or port forwarding) | Nothing. |
| Host behind a home router, no port forwarding | STUN usually works. |
| Either side behind a strict company or mobile network | TURN. |

A password placed in a web game can be read by anyone who looks, so do not put a long-lived TURN password in
one. TURN servers can hand out short-lived passwords for this; your own web server gives one to each player as
they load the game.

And remember the safety net: without any of this, browser players still play over the WebSocket.

## The native WebRTC library

Browsers have WebRTC built in. Desktop, Android and server hosts get it from a small native library. If the
library is missing for a platform, nothing breaks; browser players stay on the WebSocket. You can check:

```java
if (!FlixelJvmRtc.isAvailable()) {
  Flixel.info("WebRTC is off: " + FlixelJvmRtc.getProblem());
}
```

See [Native libraries and licenses](natives-and-licenses.md) for what to ship.

## Android

`flixelgdx-net-android` brings the native libraries for every Android CPU type and adds the `INTERNET`
permission to your app automatically. Android stops an app's network traffic soon after it leaves the screen,
so a phone makes a poor host for a long session. Joining works like anywhere else.

## Settings worth knowing

```java
session.setMaxPeers(8);        // Turn away players beyond this; they see the reason FULL.
session.setTimeout(10f);       // Seconds of silence before a peer counts as gone.
session.setSendRate(30f);      // Collect outgoing messages and send them 30 times a second.
session.kick(peer);            // Remove a player.
```

`setSendRate` trades a little delay for fewer, fuller packets. Leave it off (the default sends every frame)
until you have a reason to change it.

Next: [Replication](replication.md)
