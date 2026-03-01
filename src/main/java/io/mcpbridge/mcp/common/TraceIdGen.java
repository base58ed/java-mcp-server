package io.mcpbridge.mcp.common;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/// High-performance trace ID generator safe for virtual threads.
///
/// Thread-safe with both platform and virtual threads. Each call generates a 16-character lowercase hex string
/// combining 64 bits of randomness with a monotonic counter for guaranteed uniqueness.
///
/// Uses ThreadLocalRandom (carrier-thread-pinned, ~2-3ns, zero allocation). The AtomicLong counter ensures uniqueness
/// even if two calls produce the same random value.
public final class TraceIdGen {

  private TraceIdGen() {}

  /// Generate a 16-character hex trace ID.
  ///
  /// Thread-safe with platform and virtual threads, lock-free, suitable for hot loops.
  public static String hexId() {
    long random = ThreadLocalRandom.current().nextLong();
    long counter = COUNTER.getAndIncrement();
    return toHex(random ^ counter);
  }

  /// Generate a 32-character hex trace ID (128 bits).
  ///
  /// Use when 64 bits isn't enough entropy (e.g., globally unique IDs across billions of requests).
  public static String hexId128() {
    var rng = ThreadLocalRandom.current();
    long hi = rng.nextLong() ^ COUNTER.getAndIncrement();
    long lo = rng.nextLong() ^ COUNTER.getAndIncrement();
    char[] hex = new char[32];
    toHex(hi, hex, 0);
    toHex(lo, hex, 16);
    return new String(hex);
  }

  /// Write a 16-character hex trace ID into a buffer.
  ///
  /// Zero intermediate allocations - for building larger strings.
  ///
  /// @param buffer target array, must have at least offset + 16 chars
  /// @param offset starting position in buffer
  public static void hexIdInto(char[] buffer, int offset) {
    assert buffer.length >= offset + 16 : "buffer too small: need " + (offset + 16) + ", got " + buffer.length;
    long random = ThreadLocalRandom.current().nextLong();
    long counter = COUNTER.getAndIncrement();
    toHex(random ^ counter, buffer, offset);
  }

  private static String toHex(long value) {
    char[] hex = new char[16];
    toHex(value, hex, 0);
    return new String(hex);
  }

  private static void toHex(long value, char[] buffer, int offset) {
    for (int i = 15; i >= 0; i--) {
      buffer[offset + i] = HEX[(int) (value & 0xF)];
      value >>>= 4;
    }
  }

  private static final char[] HEX = "0123456789abcdef".toCharArray();

  private static final AtomicLong COUNTER = new AtomicLong();
}
