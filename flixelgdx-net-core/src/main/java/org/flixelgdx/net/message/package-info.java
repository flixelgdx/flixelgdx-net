/**
 * Typed messages: plain classes that are turned into bytes and back without reflection.
 *
 * <p>Mark a class with {@link org.flixelgdx.net.message.NetMessage NetMessage} and an annotation
 * processor generates a {@link org.flixelgdx.net.message.FlixelNetCodec FlixelNetCodec} for it.
 * {@link org.flixelgdx.net.message.NetRange NetRange} shrinks number fields that stay inside a
 * known range. Every codec a game uses is added to one
 * {@link org.flixelgdx.net.message.FlixelNetProtocol FlixelNetProtocol}, which both sides of a
 * connection must agree on.
 */
package org.flixelgdx.net.message;
