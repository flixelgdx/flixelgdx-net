# FlixelGDX Net Handbook

A step-by-step guide to adding multiplayer to a FlixelGDX game. It assumes you know some Java and FlixelGDX, but
no networking at all.

Read the pages in order the first time. Each one builds on the one before it, and you can stop at whichever
layer your game needs.

| Page | What you learn |
| --- | --- |
| [0. Networking basics](networking-basics.md) | Addresses, ports, hosts and clients, UDP, WebSocket and WebRTC, lag and packet loss. No code. |
| [1. Getting started](getting-started.md) | Adding the modules, your first session, and sending your own messages. |
| [2. Hosting and joining](hosting-and-joining.md) | Playing on one network and over the internet, browser players, firewalls, STUN and TURN. |
| [3. Replication](replication.md) | Letting the extension keep game objects in sync, with smoothing and instant-feeling controls. |
| [4. Dedicated servers](dedicated-server.md) | Running your game with no window on a rented computer, step by step. |
| [5. Rollback](rollback.md) | The other way to do real-time multiplayer: everyone simulates, only controls travel. |
| [6. Native libraries and licenses](natives-and-licenses.md) | What the WebRTC library is, where it comes from, and what to ship with your game. |

## Which approach is right for my game?

| Your game is... | Use | Examples |
| --- | --- | --- |
| Turn-based, or anything where you would rather send your own messages | [Plain messages](getting-started.md) | Card and board games, chat, lobbies, a shared drawing board |
| Real-time with several players or many moving things | [Replication](replication.md) | Shooters, co-op dungeon crawlers, party games, sandbox games |
| Fast, with few players, where every frame counts | [Rollback](rollback.md) | Fighting games, 1v1 platform or sports games |

You can mix them. Replication and rollback both travel over an ordinary session, so chat messages and a replicated
world can share one connection.

## The example game

The pages use one small example that grows as you read: colored squares in an arena, steered with the arrow
keys, with a chat box. Each page shows complete classes rather than fragments, so you can copy them into a
project and run them.
