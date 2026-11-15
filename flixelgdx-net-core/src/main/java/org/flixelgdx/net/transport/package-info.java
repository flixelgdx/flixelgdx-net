/**
 * The lowest layer of networking: moving packets of bytes between a host and its clients.
 *
 * <p>A {@link org.flixelgdx.net.transport.FlixelNetTransport FlixelNetTransport} sends packets on a
 * {@link org.flixelgdx.net.transport.FlixelNetChannel FlixelNetChannel} and reports what happened
 * to a {@link org.flixelgdx.net.transport.FlixelNetTransportListener FlixelNetTransportListener}
 * when the game polls it. {@link org.flixelgdx.net.transport.FlixelNetLoopback FlixelNetLoopback}
 * is a transport that needs no real network, for tests and for a host that is also a player.
 * {@link org.flixelgdx.net.transport.FlixelNetEventQueue FlixelNetEventQueue} is a building block
 * for writing new transports.
 */
package org.flixelgdx.net.transport;
