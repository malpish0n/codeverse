package dev.codeverse.model;

import static org.junit.jupiter.api.Assertions.*;

import dev.codeverse.model.CityGenerator.*;
import dev.codeverse.model.Data.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class DistrictTest {
  private FileRevision file(String name, String source) {
    return new FileRevision(name, "", 20, 100, "Java", "src", "text", source);
  }

  private String fields(int n, boolean dense) {
    StringBuilder s = new StringBuilder("class Sample {\n");
    for (int i = 0; i < n; i++)
      s.append("int metric").append(i).append(" = 1;").append(dense ? " " : "\n");
    return s.append("}").toString();
  }

  @Test
  void neighborhoodsFollowCodeProfilesAndSurviveHistoryAndSave() {
    List<FileRevision> files =
        List.of(
            file("src/home", "class Tiny {}"),
            file(
                "src/basic",
                "int run(int value) {\n" + "value += 1;\n".repeat(30) + "return value;}"),
            file("src/villa", fields(90, false)),
            file("src/tower", fields(55, true)));
    var layout = new StableLayout();
    var before = layout.reserveFiles(files);
    Map<Integer, District> blocks = new HashMap<>();
    for (var b : layout.plan().blocks()) blocks.put(b.id(), b.district());
    assertEquals(District.HOMES, blocks.get(before.get("src/home").block()));
    assertEquals(District.EVERYDAY, blocks.get(before.get("src/basic").block()));
    assertEquals(District.GARDENS, blocks.get(before.get("src/villa").block()));
    assertEquals(District.SKYLINE, blocks.get(before.get("src/tower").block()));
    layout.reserveFiles(List.of(file("src/home", fields(80, true))));
    assertEquals(before, layout.cells());
    var restored = new StableLayout();
    restored.restore(layout.state());
    assertEquals(layout.plan(), restored.plan());
    assertEquals(
        layout.reserveFiles(List.of(file("new/home", "class Tiny {}"))),
        restored.reserveFiles(List.of(file("new/home", "class Tiny {}"))));
  }

  @Test
  void districtRegionsNeverOccupyTheSameTile() {
    Set<String> centers = new HashSet<>();
    for (var d : District.values())
      for (int i = 0; i < 20; i++) {
        var region = new CityGenerator(Settings.defaults()).districtRegion(i, d);
        assertTrue(region.blocks().stream().allMatch(b -> b.district() == d));
        var p = region.roads().getFirst().points().getFirst();
        assertTrue(centers.add(p.x() + ":" + p.z()));
      }
  }

  @Test
  void infillAvoidsAllReservedParcelsAndOtherFurniture() {
    int count = 0;
    for (var district : District.values()) {
      var region = new CityGenerator(Settings.defaults()).districtRegion(0, district);
      for (var block : region.blocks()) {
        var features = UrbanInfill.forBlock(block);
        assertEquals(features, UrbanInfill.forBlock(block));
        count += features.size();
        for (int i = 0; i < features.size(); i++) {
          var f = features.get(i);
          for (var lot : block.lots()) assertTrue(UrbanInfill.clears(f, lot));
          for (int j = 0; j < i; j++) {
            var other = features.get(j);
            assertTrue(
                Math.hypot(f.x() - other.x(), f.z() - other.z()) > f.radius() + other.radius());
          }
        }
      }
    }
    assertTrue(count > 100, "Too little street furniture: " + count);
  }
}
