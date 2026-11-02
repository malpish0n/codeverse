package dev.codeverse.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class StableLayoutTest {
  @Test
  void deterministicAcrossInputOrder() {
    var a = new StableLayout();
    var b = new StableLayout();
    assertEquals(
        a.reserve(List.of("a/A.java", "z/Z.java", "a/B.java")),
        b.reserve(List.of("a/B.java", "a/A.java", "z/Z.java")));
  }

  @Test
  void unchangedFilesKeepPositionsAcrossSnapshots() {
    var a = new StableLayout();
    var before = a.reserve(List.of("a/A.java", "b/B.java"));
    var after = a.reserve(List.of("a/new.java", "new/first.java", "a/A.java", "b/B.java"));
    before.forEach((p, c) -> assertEquals(c, after.get(p)));
  }

  @Test
  void cellsDoNotCollideAtScale() {
    var paths = new ArrayList<String>();
    for (int i = 0; i < 10000; i++) paths.add("module" + (i / 100) + "/File" + i + ".java");
    long t = System.nanoTime();
    var cells = new StableLayout().reserve(paths);
    assertEquals(10000, cells.size());
    assertEquals(10000, cells.values().stream().map(c -> c.x() + ":" + c.z()).distinct().count());
    System.out.println("10k layout ms=" + (System.nanoTime() - t) / 1e6);
  }

  @Test
  void savedLayoutCanReserveMoreWithoutMovingExistingCells() {
    var a = new StableLayout();
    a.reserve(List.of("x/A", "x/B", "y/C"));
    var b = new StableLayout();
    b.restore(a.state());
    assertEquals(a.reserve(List.of("z/D", "x/E")), b.reserve(List.of("z/D", "x/E")));
  }

  @Test
  void metricsClampAndTransitionsBounded() {
    assertEquals(8, Data.height(0));
    assertEquals(120, Data.height(100000));
    assertEquals(0, Data.smooth(-1));
    assertEquals(1, Data.smooth(2));
    assertEquals(.5f, Data.smooth(.5f));
  }
}
