package dev.codeverse.model;

import static org.junit.jupiter.api.Assertions.*;

import dev.codeverse.model.CityGenerator.*;
import dev.codeverse.model.Data.Cell;
import java.util.*;
import org.junit.jupiter.api.Test;

class CityGeneratorTest {
  private List<String> paths(int count) {
    List<String> paths = new ArrayList<>();
    for (int i = 0; i < count; i++) paths.add("module" + (i / 73) + "/file" + i + ".java");
    return paths;
  }

  @Test
  void seedAndProfilesProduceReproducibleDifferentCities() {
    var a = new StableLayout();
    var b = new StableLayout();
    var config = new Settings(83021, Style.GARDEN, 1.8f);
    a.configure(config);
    b.configure(config);
    assertEquals(a.reserve(paths(1000)), b.reserve(paths(1000)));
    assertEquals(a.plan(), b.plan());
    b.configure(new Settings(83022, Style.GARDEN, 1.8f));
    assertNotEquals(a.cells(), b.reserve(paths(1000)));
    b.configure(new Settings(83021, Style.DOWNTOWN, 1.8f));
    assertNotEquals(a.cells(), b.reserve(paths(1000)));
    assertTrue(a.cells().values().stream().map(Cell::angle).distinct().count() > 100);
  }

  @Test
  void parcelFootprintsDoNotOverlapAcrossDistrictsAndRegionBorders() {
    for (Style style : Style.values())
      for (float scale : new float[] {.8f, 1.4f, 2.5f}) {
        var layout = new StableLayout();
        layout.configure(new Settings(42, style, scale));
        var cells = layout.reserve(paths(3000)).values();
        Map<String, List<Cell>> buckets = new HashMap<>();
        for (Cell cell : cells) {
          int bx = (int) Math.floor(cell.x() / 200), bz = (int) Math.floor(cell.z() / 200);
          for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
              for (Cell other : buckets.getOrDefault((bx + dx) + ":" + (bz + dz), List.of()))
                assertFalse(
                    overlap(cell, other),
                    () -> style + ": overlapping parcels " + cell + " / " + other);
          buckets.computeIfAbsent(bx + ":" + bz, k -> new ArrayList<>()).add(cell);
        }
      }
  }

  private boolean overlap(Cell a, Cell b) {
    double aa = Math.toRadians(a.angle()), bb = Math.toRadians(b.angle());
    double[][] axes = {
      {Math.cos(aa), Math.sin(aa)},
      {-Math.sin(aa), Math.cos(aa)},
      {Math.cos(bb), Math.sin(bb)},
      {-Math.sin(bb), Math.cos(bb)}
    };
    for (double[] axis : axes) {
      double distance = Math.abs((b.x() - a.x()) * axis[0] + (b.z() - a.z()) * axis[1]);
      double ra = extent(a, aa, axis), rb = extent(b, bb, axis);
      if (distance >= ra + rb) return false;
    }
    return true;
  }

  private double extent(Cell c, double angle, double[] axis) {
    return (c.width() + 4) / 2 * Math.abs(Math.cos(angle) * axis[0] + Math.sin(angle) * axis[1])
        + (c.depth() + 4) / 2 * Math.abs(-Math.sin(angle) * axis[0] + Math.cos(angle) * axis[1]);
  }

  @Test
  void streetEndpointsJoinTheConnectedNetwork() {
    var layout = new StableLayout();
    layout.reserve(paths(1000));
    var roads = layout.plan().roads();
    List<Set<Integer>> connections = new ArrayList<>();
    for (int i = 0; i < roads.size(); i++) connections.add(new HashSet<>());
    for (int i = 0; i < roads.size(); i++) {
      var road = roads.get(i);
      for (Point endpoint : List.of(road.points().getFirst(), road.points().getLast())) {
        boolean found = false;
        for (int j = 0; j < roads.size(); j++)
          if (j != i) {
            var points = roads.get(j).points();
            for (int k = 1; k < points.size(); k++)
              if (distance(endpoint, points.get(k - 1), points.get(k)) < 1) {
                found = true;
                connections.get(i).add(j);
                connections.get(j).add(i);
                break;
              }
          }
        assertTrue(found, "Disconnected street endpoint: " + endpoint);
      }
    }
    Set<Integer> visited = new HashSet<>();
    ArrayDeque<Integer> queue = new ArrayDeque<>();
    queue.add(0);
    while (!queue.isEmpty()) {
      int i = queue.removeFirst();
      if (visited.add(i)) queue.addAll(connections.get(i));
    }
    assertEquals(roads.size(), visited.size(), "Every generated street must connect to the city");
  }

  @Test
  void buildingsLeaveRoomForRoadsAndSidewalks() {
    var layout = new StableLayout();
    var cells = layout.reserve(paths(1000)).values();
    for (Cell cell : cells) {
      Point center = new Point(cell.x(), cell.z());
      double radius = Math.hypot(cell.width() + 4, cell.depth() + 4) / 2;
      for (var road : layout.plan().roads()) {
        for (int i = 1; i < road.points().size(); i++)
          assertTrue(
              distance(center, road.points().get(i - 1), road.points().get(i))
                  > radius + road.width() / 2,
              () -> "Building footprint encroaches on a street: " + cell);
      }
    }
  }

  private double distance(Point p, Point a, Point b) {
    double dx = b.x() - a.x(), dz = b.z() - a.z();
    double t =
        Math.max(
            0, Math.min(1, ((p.x() - a.x()) * dx + (p.z() - a.z()) * dz) / (dx * dx + dz * dz)));
    return Math.hypot(p.x() - a.x() - t * dx, p.z() - a.z() - t * dz);
  }

  @Test
  void oldBookmarksMigrateAndNewBookmarksRetainAllocationOrder() {
    var legacy =
        new StableLayout.State(
            Map.of("a/A", new Cell("a/A", 0, 0, 16, 0)), Map.of(), Map.of(), Map.of());
    var layout = new StableLayout();
    layout.restore(legacy);
    assertEquals(1, layout.cells().size());
    layout.configure(new Settings(97, Style.ORGANIC, 2));
    layout.reserve(paths(200));
    var copy = new StableLayout();
    copy.restore(layout.state());
    assertEquals(layout.reserve(paths(350)), copy.reserve(paths(350)));
    assertEquals(layout.plan(), copy.plan());
  }
}
