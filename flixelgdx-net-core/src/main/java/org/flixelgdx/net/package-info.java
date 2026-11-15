/**
 * Multiplayer networking for FlixelGDX.
 *
 * <h2>Overview</h2>
 *
 * <p>The extension is built in layers, and a game can stop at whichever one it needs. At the
 * bottom, a {@link org.flixelgdx.net.transport.FlixelNetTransport FlixelNetTransport} moves packets
 * of bytes between a host and its clients. Those bytes are written and read with a
 * {@link org.flixelgdx.net.FlixelNetBuffer FlixelNetBuffer}, which is made to keep packets small
 * and to stay safe when the bytes cannot be trusted.
 *
 * <h2>Packages</h2>
 *
 * <ul>
 *   <li>{@code org.flixelgdx.net} (this package) holds the byte buffer and the exception thrown
 *       for bad network data.</li>
 *   <li>{@code org.flixelgdx.net.transport} holds the transport interface, the events it reports,
 *       and a loopback transport that works inside a single program.</li>
 * </ul>
 */
package org.flixelgdx.net;
