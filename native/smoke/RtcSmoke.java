import java.nio.charset.StandardCharsets;

import org.flixelgdx.net.jvm.FlixelJvmRtc;

/**
 * A quick check that a freshly built native library works: it loads it, connects two WebRTC
 * connections to each other inside this one program, and sends a message each way on both
 * channels. The natives workflow runs it on every platform that can run what it built.
 *
 * <p>Run it with the system property flixel.net.rtc.library set to the library's path. It exits
 * with 0 when everything arrived and 1 otherwise.
 */
public final class RtcSmoke {

  private RtcSmoke() {}

  public static void main(String[] args) throws Exception {
    if (!FlixelJvmRtc.isAvailable()) {
      System.err.println("FAILED to load: " + FlixelJvmRtc.getProblem());
      System.exit(1);
    }
    int offerer = FlixelJvmRtc.create(new String[0], 0, true);
    int answerer = FlixelJvmRtc.create(new String[0], 0, false);
    if (offerer < 0 || answerer < 0) {
      System.err.println("FAILED to create connections: " + offerer + ", " + answerer);
      System.exit(1);
    }

    byte[] buffer = new byte[1 << 16];
    int open = 0;
    int received = 0;
    boolean sent = false;
    long deadline = System.nanoTime() + 30_000_000_000L;
    while (received < 4 && System.nanoTime() < deadline) {
      long event = FlixelJvmRtc.poll(buffer);
      if (event == 0) {
        Thread.sleep(5);
        continue;
      }
      if (event < 0) {
        buffer = new byte[(int) -event];
        continue;
      }
      int type = FlixelJvmRtc.eventType(event);
      int from = FlixelJvmRtc.eventHandle(event);
      int to = from == offerer ? answerer : offerer;
      String text = new String(buffer, 0, FlixelJvmRtc.eventLength(event), StandardCharsets.UTF_8);
      switch (type) {
        case FlixelJvmRtc.EVENT_DESCRIPTION -> {
          int split = text.indexOf('\n');
          FlixelJvmRtc.setRemoteDescription(to, text.substring(split + 1), text.substring(0, split));
        }
        case FlixelJvmRtc.EVENT_CANDIDATE -> {
          int split = text.indexOf('\n');
          FlixelJvmRtc.addRemoteCandidate(to, text.substring(split + 1), text.substring(0, split));
        }
        case FlixelJvmRtc.EVENT_OPEN -> open++;
        case FlixelJvmRtc.EVENT_RELIABLE, FlixelJvmRtc.EVENT_UNRELIABLE -> {
          String expected = (from == offerer ? "to offerer " : "to answerer ")
            + (type == FlixelJvmRtc.EVENT_RELIABLE ? "reliable" : "unreliable");
          if (!text.equals(expected)) {
            System.err.println("FAILED: got '" + text + "' where '" + expected + "' was expected");
            System.exit(1);
          }
          received++;
        }
        default -> {
          // State changes are not needed here.
        }
      }
      // Each side has two channels, so four opens means everything is ready.
      if (open >= 4 && !sent) {
        sent = true;
        send(offerer, FlixelJvmRtc.CHANNEL_RELIABLE, "to answerer reliable");
        send(offerer, FlixelJvmRtc.CHANNEL_UNRELIABLE, "to answerer unreliable");
        send(answerer, FlixelJvmRtc.CHANNEL_RELIABLE, "to offerer reliable");
        send(answerer, FlixelJvmRtc.CHANNEL_UNRELIABLE, "to offerer unreliable");
      }
    }
    FlixelJvmRtc.close(offerer);
    FlixelJvmRtc.close(answerer);
    if (received < 4) {
      System.err.println("FAILED: only " + received + " of 4 messages arrived (" + open + " of 4 channels opened)");
      System.exit(1);
    }
    System.out.println("OK: two WebRTC connections talked to each other on both channels.");
  }

  private static void send(int handle, int channel, String text) {
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    if (FlixelJvmRtc.send(handle, channel, bytes, 0, bytes.length) < 0) {
      System.err.println("FAILED to send '" + text + "'");
      System.exit(1);
    }
  }
}
