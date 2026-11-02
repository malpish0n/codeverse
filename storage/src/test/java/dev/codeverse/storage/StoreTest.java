package dev.codeverse.storage;

import static org.junit.jupiter.api.Assertions.*;

import dev.codeverse.model.Data.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StoreTest {
  @TempDir Path temp;

  @Test
  void persistsAndIsolatesRepositories() throws Exception {
    var snap =
        new Snapshot(
            "abc", List.of(new FileRevision("a.java", "id", 1, 2, "Java", "root", "text", "a\n")));
    try (var s = new Store(temp)) {
      s.put("one", snap);
      assertEquals(snap, s.get("one", "abc"));
      assertNull(s.get("two", "abc"));
      s.save("settings", Map.of("motion", false));
    }
    try (var s = new Store(temp)) {
      assertEquals(snap, s.get("one", "abc"));
      assertNotNull(s.load("settings", Map.class, null));
    }
  }

  @Test
  void malformedSettingsFallBack() throws Exception {
    Files.writeString(temp.resolve("settings.json"), "{broken");
    try (var s = new Store(temp)) {
      assertEquals("fallback", s.load("settings", String.class, "fallback"));
    }
  }
}
