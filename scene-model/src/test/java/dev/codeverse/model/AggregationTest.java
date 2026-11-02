package dev.codeverse.model;

import static org.junit.jupiter.api.Assertions.*;

import dev.codeverse.model.Data.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class AggregationTest {
  @Test
  void largeScenePreservesTotalsAndBoundsNodeCount() {
    var files = new ArrayList<FileRevision>();
    for (int i = 0; i < 100000; i++)
      files.add(
          new FileRevision("src/m" + (i / 100) + "/F" + i, "", 3, 5, "Java", "src", "text", ""));
    var s = Aggregation.forRendering(new Snapshot("x", files));
    assertEquals(1000, s.files().size());
    assertEquals(300000, s.files().stream().mapToInt(FileRevision::loc).sum());
    assertEquals(500000, s.files().stream().mapToLong(FileRevision::bytes).sum());
  }
}
