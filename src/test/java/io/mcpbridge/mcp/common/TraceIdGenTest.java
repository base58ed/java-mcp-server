package io.mcpbridge.mcp.common;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;

import static org.assertj.core.api.Assertions.assertThat;

final class TraceIdGenTest {

  @Test void hexId_length16() {
    assertThat(TraceIdGen.hexId()).hasSize(16);
  }

  @Test void hexId_validHexChars() {
    assertThat(TraceIdGen.hexId()).matches("[0-9a-f]{16}");
  }

  @Test void hexId_uniqueAcross1000Calls() {
    var ids = new HashSet<String>();
    for (int i = 0; i < 1000; i++) { ids.add(TraceIdGen.hexId()); }
    assertThat(ids).hasSize(1000);
  }

  @Test void hexId128_length32() {
    assertThat(TraceIdGen.hexId128()).hasSize(32);
  }

  @Test void hexId128_validHexChars() {
    assertThat(TraceIdGen.hexId128()).matches("[0-9a-f]{32}");
  }

  @Test void hexIdInto_writesToBuffer() {
    var buf = new char[32];
    TraceIdGen.hexIdInto(buf, 8);
    var id = new String(buf, 8, 16);
    assertThat(id).matches("[0-9a-f]{16}");
    // Chars before offset are untouched
    assertThat(buf[0]).isEqualTo('\0');
  }

  @Test void hexId_uniqueUnderContention() throws Exception {
    int threads = 32;
    int idsPerThread = 1_000;
    var barrier = new CyclicBarrier(threads);
    Set<String> allIds = ConcurrentHashMap.newKeySet();

    var vThreads = new Thread[threads];
    for (int t = 0; t < threads; t++) {
      vThreads[t] = Thread.ofVirtual().start(() -> {
        try { barrier.await(); } catch (Exception e) { throw new RuntimeException(e); }
        for (int i = 0; i < idsPerThread; i++) {
          allIds.add(TraceIdGen.hexId());
        }
      });
    }
    for (var vt : vThreads) { vt.join(10_000); }

    assertThat(allIds).hasSize(threads * idsPerThread);
  }
}