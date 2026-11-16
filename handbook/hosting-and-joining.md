# 2. Hosting and joining

On this page you get players on different computers, phones and browsers into one game: on one home network
first, then over the internet. If *port*, *router* or *firewall* are unfamiliar, read
[Networking basics](networking-basics.md) first.

## Three roads, chosen for you

Different kinds of players reach a host in different ways. You never pick in code: `FlixelNet.host` opens every
road at once, on one port number, and `FlixelNet.join` takes the best one the platform has.

| Road | Used by | Good at | Weak at |
| --- | --- | --- | --- |
| **UDP** | Desktop and Android players | Fastest; positions never wait behind a lost packet | Browsers are not allowed to use it |
| **WebSocket** | Browser players, at first | Every browser can always open one | Everything is reliable, so one lost packet makes the rest wait |
| **WebRTC** | Browser players, after a moment | Gives browsers unreliable delivery too | Needs a WebSocket (or other channel) to set it up |

A browser player starts on the WebSocket and can play right away. In the background, the two sides use that
WebSocket to set up a WebRTC connection. When it is ready, traffic moves over without the game noticing. If
WebRTC cannot connect (a strict firewall, for example), the player simply stays on the WebSocket.

`peer.getRoute()` tells you which road a peer is on: `"UDP"`, `"WebSocket"`, `"WebRTC"` or `"Loopback"`. Showing
it on a debug screen is handy.

## Addresses

```java
FlixelNet.join(session, "192.168.1.20:7777");        // Desktop and Android: UDP. Browser: WebSocket.
FlixelNet.join(session, "ws://192.168.1.20:7777");   // The way a browser joins, from any platform.
FlixelNet.join(session, "wss://play.example.com");   // The same, encrypted (see "Browser players" below).
```

The host always uses the same call, whoever is going to join:

```java
FlixelNet.host(session, 7777);   // Listens for UDP and WebSocket players on port 7777.
```

Pick any port from 1024 to 65535 that nothing else on the computer uses. This handbook uses 7777.

## Playing on one home network

Players on the same Wi-Fi or wired network can reach each other with almost no setup.

### 1. Start hosting

Start the game on the host computer and host.

### 2. Find the host's local address

The host needs its own private address, which usually starts with `192.168.` or `10.`:

| System | How |
| --- | --- |
| Windows | Run `ipconfig` in a terminal and look for "IPv4 Address". |
| macOS | Run `ipconfig getifaddr en0` (Wi-Fi) in Terminal, or look in System Settings > Network. |
| Linux | Run `hostname -I`; the first address is usually the one. |
| Android | Settings > About phone > Status (the exact place varies by phone). |

### 3. Allow the game through the host's firewall

Most "it just times out" problems are a firewall on the host quietly refusing the connection.

- **Windows** asks the first time a Java program opens a port. Allow it on **private** networks. If you clicked
  away, open "Allow an app through Windows Firewall" and tick Java for private networks.
- **macOS** asks "Do you want the application to accept incoming network connections?". Click Allow.
- **Linux** usually has no firewall running. If yours uses `ufw`:

  ```bash
  sudo ufw allow 7777/udp
  sudo ufw allow 7777/tcp
  ```

Open the port for **both UDP and TCP**: UDP for desktop and Android players, TCP for browser players (a
WebSocket runs on TCP).

### 4. Join

Everyone else joins `thataddress:7777`, for example `192.168.1.20:7777`. Browser players join
`ws://192.168.1.20:7777`.

### Testing on one computer

Run two copies of the game. One hosts, and the other joins `127.0.0.1:7777`, which always means "this computer".
No firewall is involved.

## Playing over the internet

A home router hides the computers behind it, so a friend across the internet cannot reach the host's private
address. There are three ways around that, from simplest to most robust:

1. **Port forwarding.** The host logs in to their router (its address is usually printed on it, often
   `192.168.1.1` or `192.168.0.1`) and adds a rule: port 7777, UDP and TCP, goes to the host computer's local
   address. Players then join the host's **public** address, which sites such as `ifconfig.me` show. It is free,
   but every host has to do it, and some internet providers (especially mobile ones) make it impossible.
2. **A dedicated server.** Rent a small computer with a public address and run the game there. Nobody has to set
   up their router, and the game does not end when one player quits. This is the usual choice for a released
   game; see [Dedicated servers](dedicated-server.md).
3. **STUN and TURN** helper servers (below). They only help WebRTC connections, so they suit games whose
   players are mostly in browsers.

## Browser players

Browsers add two rules of their own.

**A browser cannot host.** Web pages are not allowed to accept connections. A browser player can only join a
desktop, Android or dedicated host.

**A secure page needs a secure connection.** If your game's page is served over `https://` (every real website
is), the browser refuses to open a plain `ws://` connection to anything except `localhost`. It must be `wss://`,
which needs a certificate. The host itself only speaks plain `ws://`, so put a **reverse proxy** in front of it:
a small web server that owns the certificate, accepts `wss://` from browsers, and passes the traffic on to the
game. With [Caddy](https://caddyserver.com), which gets free certificates by itself, the whole configuration is:

```
play.example.com {
  reverse_proxy localhost:7777
}
```

Browser players then join `wss://play.example.com`. Desktop and Android players keep using
`play.example.com:7777` directly. The WebRTC upgrade still works through the proxy, because only the setup
travels over the WebSocket. While developing, none of this matters: a page served from `http://localhost` may
use `ws://` freely.

## STUN and TURN

These only matter for **browser players**, and only for the WebRTC part.

A browser always reaches the host through the WebSocket first, so the host must already be reachable: on the same
network, at a public address, or through port forwarding. STUN and TURN cannot fix a host nobody can reach. What
they do is help the *upgrade* to WebRTC find a path when the WebSocket gets through but WebRTC's packets would
not.

- A **STUN** server is a mirror. A computer asks it "what address do you see me as?", learns its public address,
  and tells the other side. That is often enough to get packets through two home routers. STUN servers are
  light, and free public ones exist.
- A **TURN** server is a go-between. When no direct path exists at all, both sides send to the TURN server and
  it passes every packet along. That always works, but it costs its owner bandwidth, so it needs a user name and
  password. You either rent one or run one yourself ([coturn](https://github.com/coturn/coturn) is the usual
  choice).

```java
// Before hosting or joining, on the host and on the clients:
FlixelNet.getIce().add("stun:stun.example.com:3478");
FlixelNet.getIce().add("turn:turn.example.com:3478", "user", "password");
```

| Situation | What you need |
| --- | --- |
| Everyone on one local network | Nothing. |
| The host has a public address with the port open for UDP and TCP (a rented server, or both forwarded) | Nothing. |
| Only TCP reaches the host (only TCP forwarded, or a proxy that only passes TCP) | STUN often works; TURN always does. |
| The player is on a strict company, school or mobile network that blocks UDP | TURN. |

A password placed in a web game can be read by anyone who looks, so do not put a long-lived TURN password in
one. TURN servers can hand out short-lived passwords for this; your own web server gives one to each player as
they load the game.

And remember the safety net: without any of this, browser players still play over the WebSocket. They just get
the stutter of reliable-only delivery on a bad connection.

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

Set these on the session before hosting or joining:

```java
session.setMaxPeers(8);        // Turn away players beyond this. They are told FULL.
session.setTimeout(10f);       // Seconds of silence before a peer counts as gone.
session.setSendRate(30f);      // Collect outgoing messages and send them 30 times a second.
```

And at any time, on the host:

```java
session.kick(peer);            // Remove a player. They are told KICKED.
```

`setSendRate` trades a little delay for fewer, fuller packets. Leave it off (the default sends every frame) until
you have a reason to change it.

## When joining fails

`session.onJoinFailed` fires with a reason:

| Reason | What it means | What to check |
| --- | --- | --- |
| `TIMEOUT` | The host never answered. | The address and port, the host's firewall, and that the host is actually hosting. Over the internet: port forwarding or a public address. |
| `REFUSED` | Nothing was listening at that address, or the host turned the connection away. | The port number, and that the game on the host started hosting. |
| `VERSION_MISMATCH` | The two builds have different protocols. | Run the same build on both, and rebuild after changing messages. |
| `FULL` | The host reached its maximum number of players. | `setMaxPeers` on the host. |
| `ERROR` | Something was broken in what arrived. | The host's log. |

A browser player that cannot join at all usually hit the secure-connection rule above: the page is `https://`
and the address is `ws://`. The browser's developer console says so.

Next: [Replication](replication.md)
