/**
 * Multiplayer networking for FlixelGDX.
 *
 * <h2>Overview</h2>
 *
 * <p>The extension is built in layers, and a game can stop at whichever one it needs.
 *
 * <p>Most games start with a {@link org.flixelgdx.net.FlixelNetSession FlixelNetSession}: one
 * host, the clients that joined it, and typed messages flowing between them. A message is a plain
 * class marked with {@link org.flixelgdx.net.message.NetMessage NetMessage}, and the code that
 * turns it into bytes is generated at compile time. Everyone in a session is a
 * {@link org.flixelgdx.net.FlixelNetPeer FlixelNetPeer}.
 *
 * <p>Underneath, a {@link org.flixelgdx.net.transport.FlixelNetTransport FlixelNetTransport} moves
 * packets of bytes between a host and its clients. Those bytes are written and read with a
 * {@link org.flixelgdx.net.FlixelNetBuffer FlixelNetBuffer}, which is made to keep packets small
 * and to stay safe when the bytes cannot be trusted.
 *
 * <h2>Packages</h2>
 *
 * <ul>
 *   <li>{@code org.flixelgdx.net} (this package) holds the session, its peers, the byte buffer,
 *       and the exception thrown for bad network data.</li>
 *   <li>{@code org.flixelgdx.net.message} holds the annotations that mark message classes and the
 *       protocol that lists them.</li>
 *   <li>{@code org.flixelgdx.net.transport} holds the transport interface, the events it reports,
 *       and a loopback transport that works inside a single program.</li>
 * </ul>
 */
package org.flixelgdx.net;
