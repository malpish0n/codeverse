package dev.codeverse.model;

import dev.codeverse.model.CityGenerator.*;
import java.util.*;

/** Low-rise street furniture in verified gaps, never inside a reserved building parcel. */
public final class UrbanInfill {
  public enum Kind {
    TREE,
    PLANTER,
    BENCH,
    PARKING
  }

  public record Feature(float x, float z, float angle, Kind kind, float radius) {}

  public static List<Feature> forBlock(Block block) {
    if (block.park()) return List.of();
    var p = block.outline();
    Point a = p.get(0), b = p.get(3), c = p.get(2), d = p.get(1);
    float angle = (float) Math.toDegrees(Math.atan2(b.z() - a.z(), b.x() - a.x()));
    float width = (float) Math.hypot(b.x() - a.x(), b.z() - a.z());
    float depth = (float) Math.hypot(d.x() - a.x(), d.z() - a.z());
    float margin = Math.max(15, Math.min(width, depth) * .20f);
    int columns = Math.max(2, (int) (width / 12)), rows = Math.max(2, (int) (depth / 12));
    List<Feature> candidates = new ArrayList<>();
    Random random = new Random(block.id() * 73471L + 913);
    for (int row = 1; row < rows; row++)
      for (int col = 1; col < columns; col++) {
        float u = (float) col / columns, v = (float) row / rows;
        if (u * width < margin
            || (1 - u) * width < margin
            || v * depth < margin
            || (1 - v) * depth < margin) continue;
        float x = (1 - v) * ((1 - u) * a.x() + u * b.x()) + v * ((1 - u) * d.x() + u * c.x());
        float z = (1 - v) * ((1 - u) * a.z() + u * b.z()) + v * ((1 - u) * d.z() + u * c.z());
        Kind kind =
            switch (block.district()) {
              case HOMES, GARDENS -> random.nextInt(4) == 0 ? Kind.BENCH : Kind.TREE;
              case EVERYDAY -> random.nextBoolean() ? Kind.PARKING : Kind.TREE;
              case SKYLINE -> random.nextBoolean() ? Kind.PLANTER : Kind.BENCH;
            };
        float radius = kind == Kind.PARKING ? 5 : kind == Kind.TREE ? 3.5f : 3;
        var f = new Feature(x, z, angle, kind, radius);
        boolean clear = block.lots().stream().allMatch(l -> clears(f, l));
        if (clear
            && block.trees().stream().allMatch(t -> Math.hypot(x - t.x(), z - t.z()) > radius + 4))
          candidates.add(f);
      }
    Collections.shuffle(candidates, random);
    List<Feature> result = new ArrayList<>();
    for (var f : candidates) {
      if (result.stream()
          .allMatch(
              other -> Math.hypot(f.x - other.x, f.z - other.z) > f.radius + other.radius + 1))
        result.add(f);
      if (result.size() >= 32) break;
    }
    return List.copyOf(result);
  }

  public static boolean clears(Feature f, Lot lot) {
    double angle = Math.toRadians(lot.angle()), dx = f.x - lot.x(), dz = f.z - lot.z();
    double x = Math.abs(dx * Math.cos(angle) + dz * Math.sin(angle));
    double z = Math.abs(-dx * Math.sin(angle) + dz * Math.cos(angle));
    double outsideX = Math.max(0, x - lot.width() / 2 - 2),
        outsideZ = Math.max(0, z - lot.depth() / 2 - 2);
    return Math.hypot(outsideX, outsideZ) > f.radius + 1;
  }
}
