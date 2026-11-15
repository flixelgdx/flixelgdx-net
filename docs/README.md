# FlixelGDX Net Handbook

A step-by-step guide to adding multiplayer to a FlixelGDX game. Read the pages in order the first time; each one
builds on the one before it, and you can stop at whichever layer your game needs.

| Page | What you learn |
| --- | --- |
| [1. Getting started](getting-started.md) | Adding the modules, your first session, sending typed messages. |
| [2. Hosting and joining](hosting-and-joining.md) | Desktop, Android and web players in one game; ports, routers, STUN and TURN. |
| [3. Replication](replication.md) | Letting the extension keep game objects in sync, with smoothing and instant controls. |
| [4. Dedicated servers](dedicated-server.md) | Running your game with no window on a rented computer. |
| [5. Rollback](rollback.md) | The other way to do multiplayer: everyone simulates, only controls travel. |
| [6. Native libraries and licenses](natives-and-licenses.md) | What the WebRTC library is, where it comes from, and what to ship with your game. |

## Which approach is right for my game?

| Your game is... | Use |
| --- | --- |
| A lobby, chat, turn-based, or anything where you would rather send your own messages | [Messages](getting-started.md) only |
| Real-time with several players or many moving things: shooters, co-op, party games | [Replication](replication.md) |
| A fast one-on-one or small game where every frame counts: fighting, sports, racing | [Rollback](rollback.md) |

You can mix them. Replication and rollback both travel over an ordinary session, so chat messages and a replicated
world can share one connection.
