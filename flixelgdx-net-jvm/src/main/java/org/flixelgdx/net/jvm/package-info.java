/**
 * Real network transports for desktop, Android and dedicated servers.
 *
 * <p>{@link org.flixelgdx.net.jvm.FlixelJvmNet FlixelJvmNet} installs them so
 * {@link org.flixelgdx.net.FlixelNet FlixelNet} can use them.
 * {@link org.flixelgdx.net.jvm.FlixelJvmDatagramSocket FlixelJvmDatagramSocket} is a UDP socket
 * for direct connections between desktop and Android players, and
 * {@link org.flixelgdx.net.jvm.FlixelJvmWebSocketTransport FlixelJvmWebSocketTransport} is how
 * players in a browser get in.
 */
package org.flixelgdx.net.jvm;
