# 0. Networking basics

New to multiplayer? Start here. This page explains the words the rest of the handbook uses, with no code. If you
already know what a port, UDP and latency are, skip to [Getting started](getting-started.md).

## Computers, addresses and ports

Every computer on a network has an **IP address**, like a street address for a building. It looks like
`192.168.1.20` (the older, common kind, IPv4) or `2001:db8::1` (the newer kind, IPv6).

One computer runs many programs at once, so an address alone is not enough. Each program that talks on the
network picks a **port**, a number from 1 to 65535, like an apartment number in that building. Your game
picks one (this handbook uses `7777`), and players reach it at `address:port`, for example `192.168.1.20:7777`.

Two special addresses are worth knowing:

- `127.0.0.1` (also called `localhost`) always means "this same computer". Use it to run two copies of your game
  on one computer while testing.
- Addresses starting with `192.168.`, `10.` or `172.16.` to `172.31.` are **private**. They only work inside one
  home or office network. A player across the internet cannot reach them.

## Host and client

In this extension, one player's game (or a server) **hosts**: it opens its port and waits. Everyone else
**joins**: they connect to the host's address. The joining games are **clients**.

The host is in charge. Clients send what they want to do; the host decides what actually happens and tells
everyone. This is called **client-server**, and it is how most online games work, because:

- **Cheating is hard.** A client can only *ask*. A tampered client saying "I am at the finish line" is ignored,
  because only the host's word counts.
- **There is one truth.** If two players grab the same coin, the host decides who got it, and everyone sees the
  same answer.

When a player's own game is the host, it is a **listen server** (that player hosts and plays at the same time).
When the host is a computer nobody plays on, it is a **dedicated server**.

## Packets

Data travels over a network in small chunks called **packets**, usually under about 1,400 bytes each. A message
such as "player 2 moved to (300, 200)" fits in one packet with plenty of room to spare. The extension packs many
small messages into each packet for you.

Packets travel through many machines on the way, and the trip is not perfect:

- **Latency** is how long a packet takes to arrive. **Ping** is the round trip, there and back, in
  milliseconds. On one home network it is a few milliseconds; across a country, 30 to 80; across the world,
  200 or more.
- **Packet loss** is when a packet never arrives. On good connections it is rare; on busy Wi-Fi or mobile data
  it can be a few percent.
- **Jitter** is when packets take different amounts of time, so they arrive bunched up or out of order.
- **Bandwidth** is how much data a connection can carry each second. Games use little, but sending a lot
  of data to many players adds up on the host.

None of these can be removed. Good netcode hides them, and most of this extension exists to do that.

## Reliable and unreliable

There are two ways to send a packet, and games need both.

- **Reliable**, like a registered letter: the receiver confirms each one, and anything lost is sent again. It
  always arrives, in order. The cost is that if one packet is lost, everything behind it waits for the resend.
- **Unreliable**, like a postcard: sent once, never resent. Fast, and nothing ever waits, but some never arrive.

Use **reliable** for things that must not be missed: chat, "player joined", "you picked up the key". Use
**unreliable** for things that are replaced a moment later anyway: positions, aim direction. If one position is
lost, the next one is already on its way, and waiting for the old one would only make things worse.

In this extension, the two are called `FlixelNetChannel.RELIABLE` and `FlixelNetChannel.UNRELIABLE`.

## The kinds of connection

These are the actual ways bytes travel. You do not choose between them in code; the extension picks the right
one for each player. It still helps to know what they are.

### UDP

The internet's "postcard" service: send a packet to an address and hope. It is the fastest option and has no
built-in reliability, which is exactly why games like it: the extension adds its own reliable channel on top,
and keeps the unreliable one truly unreliable.

**Used by:** desktop and Android players.

### TCP

The internet's "phone call" service: a steady stream where everything arrives, in order. Web pages and
downloads use it. It is reliable *only*, so a single lost packet holds up everything behind it. That is fine
for a web page and bad for a fast game. You will not use TCP directly; it matters because WebSocket runs on top
of it.

### WebSocket

A connection a web page can open, carried over TCP. Web pages are **not allowed** to use UDP (for security
reasons), so WebSocket is the one connection every browser can always open. Its downside is that of TCP:
everything is reliable, so lost packets cause stutter.

**Used by:** browser players, at first. Addresses start with `ws://` (plain) or `wss://` (encrypted).

### WebRTC

A newer browser feature built for video calls, which can also send game data with **both** reliable and
unreliable delivery. It runs over UDP underneath. Its catch is that two computers cannot start a WebRTC
connection on their own: they first have to swap connection details through some other channel (called
**signaling**).

**Used by:** browser players, once it is set up. The extension uses the WebSocket for signaling, then moves
the game's traffic to WebRTC. If WebRTC cannot connect, the player stays on the WebSocket.

### Loopback

Not a real network at all: two sessions in the same program, passing packets in memory. It is used for tests,
for a host whose own player joins its own game, and for trying out lag and packet loss on purpose.

### Summary

| Connection | Reliable | Unreliable | Who uses it here |
| --- | --- | --- | --- |
| UDP | Added by the extension | Yes | Desktop, Android, servers |
| WebSocket | Yes | No (approximated by skipping stale data) | Browsers, at first |
| WebRTC | Yes | Yes | Browsers, after the upgrade |
| Loopback | Yes | Yes | Tests, a host's own player |

## Routers, firewalls and NAT

At home, many devices share one public address. Your **router** gives each device a private address
(`192.168.x.x`) and shares one public address for all of them. This is called **NAT**, network address
translation.

This works like an office building where every desk shares one street address. Letters going *out* are easy.
A letter coming *in* addressed only to the building leaves the mail room asking which desk it is for, and by
default it is thrown away. That is why a player at home usually cannot host for friends across the internet
without extra setup:

- **Port forwarding** tells the router "anything for port 7777 goes to this computer".
- A **dedicated server** has its own public address, so no router is in the way.
- **STUN** and **TURN** servers help WebRTC find a path through routers (see
  [Hosting and joining](hosting-and-joining.md#stun-and-turn)).

A **firewall** is a guard on a computer or network that blocks connections it was not told to allow. Windows
and macOS usually ask the first time your game opens a port; choose to allow it. On a rented server you open
the port yourself.

## Ticks and rates

Games update in steps called frames, usually 60 a second. Networked games often add fixed-length steps called
**ticks**, so every computer moves things by exactly the same amount, no matter how fast it draws. A few rates
come up in this handbook:

- **Tick rate**: how many simulation steps happen per second (for example 60).
- **Send rate** or **snapshot rate**: how many times per second updates are sent (for example 20).
- **Update rate** of a dedicated server: how many times per second it runs its game loop.

## Three ways to keep a game in sync

This is the big choice. Pick based on your game, not on what sounds cool.

1. **Plain messages.** You decide what to send and when. Simple and flexible, but you write all the syncing
   yourself. Best for chat, lobbies and turn-based or card games, where a few hundred milliseconds of delay do
   not matter. See [Getting started](getting-started.md).
2. **Replication.** The host runs the game and regularly sends what everything looks like. Clients show
   other things slightly in the past, smoothly, and move their own character instantly with **prediction**.
   Best for most real-time games with several players: shooters, co-op, party games, sandbox games. See
   [Replication](replication.md).
3. **Rollback.** Every computer runs the whole game, and only controls are sent. When a guess about someone
   else's controls was wrong, the game rewinds a few frames and replays them. Your own controls always feel
   instant, but the game must be strictly **deterministic** (same inputs always give exactly the same result).
   Best for fast games with few players where every frame counts: fighting games, 1v1 action. See
   [Rollback](rollback.md).

## Glossary

| Word | Meaning |
| --- | --- |
| Authority | Who has the final say about something. Here it is almost always the host. |
| Client | A game that joined a host. |
| Codec | Code that turns a message into bytes and back. The extension writes these for you. |
| Desync | When two computers' copies of a game no longer match. A rollback bug. |
| Deterministic | Same starting state and same inputs always give exactly the same result. |
| Host | The game or server others join. |
| Interpolation | Sliding smoothly between two known positions, so movement does not jump. |
| Peer | Anyone on the other end of a connection. |
| Ping | Round-trip time to a peer, in milliseconds. |
| Prediction | Moving your own character right away, before the host confirms it. |
| Protocol | The list of messages a game can send. Both sides must have the same one. |
| Reconciliation | Fixing a prediction when the host's answer disagrees with it. |
| Session | One connection to a game: the host side or a client side. |
| Snapshot | What the host says a set of things looks like at one moment. |
| Transport | The part that actually moves bytes: UDP, WebSocket, WebRTC or loopback. |

Next: [Getting started](getting-started.md)
