/*
 * MIT License
 *
 * Copyright (c) 2026 stringdotjar
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.flixelgdx.net.transport;

import org.flixelgdx.collections.FlixelArray;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The helper servers a WebRTC connection may use to find a path between two computers.
 *
 * <p>Most home computers sit behind a router that hides their real address, a bit like an office
 * where every desk shares one street address. Two kinds of helper get around that:
 *
 * <ul>
 *   <li>A <b>STUN</b> server is a mirror: a computer asks it "what address do you see me as?" and
 *       then tells the other side. It is cheap, and free public ones exist. Its address looks
 *       like {@code "stun:stun.example.com:3478"}.</li>
 *   <li>A <b>TURN</b> server is a go-between that passes every packet along when no direct path
 *       can be found at all. It costs its owner real bandwidth, so it needs a user name and a
 *       password. Its address looks like {@code "turn:turn.example.com:3478"}.</li>
 * </ul>
 *
 * <p>With no servers at all, WebRTC still works between computers on the same local network and
 * with a host that has a public address, such as a rented server. When it cannot connect, the
 * game quietly keeps using its WebSocket instead, so nothing breaks; it is only a little less
 * smooth.
 *
 * <pre>{@code
 * FlixelNet.getIce().add("stun:stun.example.com:3478");
 * FlixelNet.getIce().add("turn:turn.example.com:3478", "player", "secret");
 * }</pre>
 *
 * <p>Both the host and the players joining it should be given the same servers, before hosting
 * or joining.
 */
public final class FlixelNetIceConfig {

  private final FlixelArray<String> urls = new FlixelArray<>();
  private final FlixelArray<String> usernames = new FlixelArray<>();
  private final FlixelArray<String> credentials = new FlixelArray<>();

  /** Creates a configuration with no servers. */
  public FlixelNetIceConfig() {}

  /**
   * Adds a server that needs no login, which is the usual case for STUN.
   *
   * @param url The server's address, such as {@code "stun:stun.example.com:3478"}.
   * @return This configuration, so calls can be chained.
   */
  @NotNull
  public FlixelNetIceConfig add(@NotNull String url) {
    return add(url, null, null);
  }

  /**
   * Adds a server that needs a login, which is the usual case for TURN.
   *
   * @param url The server's address, such as {@code "turn:turn.example.com:3478"}.
   * @param username The user name to log in with, or {@code null} for none.
   * @param credential The password to log in with, or {@code null} for none.
   * @return This configuration, so calls can be chained.
   * @throws IllegalArgumentException If the address does not start with {@code stun:},
   *     {@code turn:} or {@code turns:}.
   */
  @NotNull
  public FlixelNetIceConfig add(@NotNull String url, @Nullable String username, @Nullable String credential) {
    if (!url.startsWith("stun:") && !url.startsWith("turn:") && !url.startsWith("turns:")) {
      throw new IllegalArgumentException("A server address must start with stun:, turn: or turns:, got '" + url + "'.");
    }
    urls.add(url);
    usernames.add(username);
    credentials.add(credential);
    return this;
  }

  /** Removes every server. */
  public void clear() {
    urls.clear();
    usernames.clear();
    credentials.clear();
  }

  /**
   * Returns how many servers were added.
   *
   * @return The number of servers.
   */
  public int getCount() {
    return urls.getSize();
  }

  /**
   * Returns a server's address as it was added.
   *
   * @param index A position from 0 to {@link #getCount()} minus one.
   * @return The address.
   */
  @NotNull
  public String getUrl(int index) {
    return urls.get(index);
  }

  /**
   * Returns a server's user name.
   *
   * @param index A position from 0 to {@link #getCount()} minus one.
   * @return The user name, or {@code null} if the server needs none.
   */
  @Nullable
  public String getUsername(int index) {
    return usernames.get(index);
  }

  /**
   * Returns a server's password.
   *
   * @param index A position from 0 to {@link #getCount()} minus one.
   * @return The password, or {@code null} if the server needs none.
   */
  @Nullable
  public String getCredential(int index) {
    return credentials.get(index);
  }

  /**
   * Returns a server's address with its login folded in, in the form
   * {@code turn:user:password@host:port}, which is how native WebRTC libraries expect it.
   *
   * @param index A position from 0 to {@link #getCount()} minus one.
   * @return The address, with the login in it when the server has one.
   */
  @NotNull
  public String getUrlWithLogin(int index) {
    String url = urls.get(index);
    String username = usernames.get(index);
    if (username == null) {
      return url;
    }
    int colon = url.indexOf(':');
    String credential = credentials.get(index);
    return url.substring(0, colon + 1) + username + ":" + (credential == null ? "" : credential) + "@"
        + url.substring(colon + 1);
  }

  /**
   * Returns the servers as the JSON list a browser's {@code RTCPeerConnection} takes.
   *
   * @return A JSON array, {@code "[]"} when there are no servers.
   */
  @NotNull
  public String toJson() {
    StringBuilder json = new StringBuilder("[");
    for (int i = 0, n = urls.getSize(); i < n; i++) {
      if (i > 0) {
        json.append(',');
      }
      json.append("{\"urls\":");
      quote(json, urls.get(i));
      if (usernames.get(i) != null) {
        json.append(",\"username\":");
        quote(json, usernames.get(i));
        json.append(",\"credential\":");
        quote(json, credentials.get(i) == null ? "" : credentials.get(i));
      }
      json.append('}');
    }
    return json.append(']').toString();
  }

  private static void quote(StringBuilder json, String text) {
    json.append('"');
    for (int i = 0, n = text.length(); i < n; i++) {
      char c = text.charAt(i);
      if (c == '"' || c == '\\') {
        json.append('\\').append(c);
      } else if (c < 0x20) {
        json.append(' ');
      } else {
        json.append(c);
      }
    }
    json.append('"');
  }
}
