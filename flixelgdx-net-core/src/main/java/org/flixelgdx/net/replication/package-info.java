/**
 * Keeps game objects the same on every computer in a session.
 *
 * <p>Sending messages by hand works for chat and one-off events, but most of a multiplayer game
 * is "this thing is here now", repeated for every thing, many times a second. This package does
 * that part: the host puts entities into a
 * {@link org.flixelgdx.net.replication.FlixelNetWorld FlixelNetWorld} and every client sees them
 * appear, move smoothly, and disappear.
 *
 * <ul>
 *   <li>{@link org.flixelgdx.net.replication.FlixelNetEntity FlixelNetEntity} is something the
 *       host moves and clients watch.</li>
 *   <li>{@link org.flixelgdx.net.replication.FlixelNetPawn FlixelNetPawn} is something a player
 *       controls; it answers that player's controls at once and is corrected by the host.</li>
 * </ul>
 */
package org.flixelgdx.net.replication;
