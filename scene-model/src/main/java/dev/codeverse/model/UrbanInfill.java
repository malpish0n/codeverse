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

  public record AccessPath(Point start, Point end, float width) {}

  public static List<AccessPath> paths(Block block) {
    if (block.park()) return List.of();
    var corners = block.outline();
    float cx = 0, cz = 0;
    for (var p : corners) {
      cx += p.x() / 4;
      cz += p.z() / 4;
    }
    double[] nx = new double[4], nz = new double[4], line = new double[4];
    double minEdge = Double.POSITIVE_INFINITY;
    for (int i = 0; i < 4; i++) {
      var a = corners.get(i);
      var b = corners.get((i + 1) % 4);
      minEdge = Math.min(minEdge, Math.hypot(b.x() - a.x(), b.z() - a.z()));
    }
    for (int i = 0; i < 4; i++) {
      var a = corners.get(i);
      var b = corners.get((i + 1) % 4);
      double length = Math.hypot(b.x() - a.x(), b.z() - a.z());
      nx[i] = -(b.z() - a.z()) / length;
      nz[i] = (b.x() - a.x()) / length;
      if (nx[i] * (cx - a.x()) + nz[i] * (cz - a.z()) < 0) {
        nx[i] = -nx[i];
        nz[i] = -nz[i];
      }
      double offset = minEdge * .16;
      for (var lot : block.lots()) {
        double angle = Math.toRadians(lot.angle());
        double extent =
            (lot.width() / 2 + 2) * Math.abs(nx[i] * Math.cos(angle) + nz[i] * Math.sin(angle))
                + (lot.depth() / 2 + 2)
                    * Math.abs(-nx[i] * Math.sin(angle) + nz[i] * Math.cos(angle));
        offset =
            Math.min(offset, nx[i] * (lot.x() - a.x()) + nz[i] * (lot.z() - a.z()) - extent - 2);
      }
      line[i] = nx[i] * a.x() + nz[i] * a.z() + Math.max(1, offset);
    }
    List<Point> ring = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      int prev = (i + 3) % 4;
      double det = nx[prev] * nz[i] - nx[i] * nz[prev];
      ring.add(
          new Point(
              (float) ((line[prev] * nz[i] - line[i] * nz[prev]) / det),
              (float) ((nx[prev] * line[i] - nx[i] * line[prev]) / det)));
    }
    List<AccessPath> paths = new ArrayList<>();
    for (int i = 0; i < 4; i++) paths.add(new AccessPath(ring.get(i), ring.get((i + 1) % 4), 3));
    for (var lot : block.lots()) {
      Point start = new Point(lot.x(), lot.z()), nearest = null;
      double best = Double.POSITIVE_INFINITY;
      for (int i = 0; i < 4; i++) {
        Point candidate = closest(start, ring.get(i), ring.get((i + 1) % 4));
        double distance = Math.hypot(start.x() - candidate.x(), start.z() - candidate.z());
        if (distance < best) {
          best = distance;
          nearest = candidate;
        }
      }
      paths.add(new AccessPath(start, nearest, 2.5f));
    }
    return List.copyOf(paths);
  }

  private static Point closest(Point p, Point a, Point b) {
    float dx = b.x() - a.x(), dz = b.z() - a.z(), length = dx * dx + dz * dz;
    float t =
        length == 0
            ? 0
            : Math.max(0, Math.min(1, ((p.x() - a.x()) * dx + (p.z() - a.z()) * dz) / length));
    return new Point(a.x() + t * dx, a.z() + t * dz);
  }

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
    var paths = paths(block);
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
        clear &=
            paths.stream()
                .allMatch(
                    path -> {
                      Point nearest = closest(new Point(x, z), path.start(), path.end());
                      return Math.hypot(x - nearest.x(), z - nearest.z())
                          > radius + path.width() / 2;
                    });
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
