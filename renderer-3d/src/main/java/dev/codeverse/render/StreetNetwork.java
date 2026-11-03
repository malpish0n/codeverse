package dev.codeverse.render;

import dev.codeverse.model.CityGenerator.*;
import java.util.*;

/** Road graph split at generated intersections; vehicles choose a new street at junctions. */
final class StreetNetwork {
  record Pose(float x, float z, float angle) {}

  private static final class Node {
    final Point point;
    final List<Edge> edges = new ArrayList<>();

    Node(Point point) {
      this.point = point;
    }
  }

  static final class Edge {
    final int id;
    final Node from, to;
    final float width, speed, length;
    final List<Point> points;
    final float[] distances;

    Edge(int id, Node from, Node to, List<Point> points, float width, boolean avenue) {
      this.id = id;
      this.from = from;
      this.to = to;
      this.points = points;
      this.width = width;
      speed = avenue ? 16 : 10;
      distances = new float[points.size()];
      for (int i = 1; i < points.size(); i++)
        distances[i] = distances[i - 1] + distance(points.get(i - 1), points.get(i));
      length = distances[distances.length - 1];
    }

    float trim(boolean forward, boolean start) {
      Node node = (forward == start) ? from : to;
      float radius = width * .65f;
      for (var e : node.edges) radius = Math.max(radius, e.width * .65f);
      return Math.min(length * .22f, radius);
    }

    float usable(boolean forward) {
      return length - trim(forward, true) - trim(forward, false);
    }

    Pose pose(float along, boolean forward) {
      float d = Math.max(0, Math.min(length, forward ? along : length - along));
      int i = Arrays.binarySearch(distances, d);
      if (i < 0) i = -i - 1;
      i = Math.max(1, Math.min(i, points.size() - 1));
      Point a = points.get(i - 1), b = points.get(i);
      float span = distances[i] - distances[i - 1],
          t = span == 0 ? 0 : (d - distances[i - 1]) / span;
      float dx = (b.x() - a.x()) / Math.max(.001f, span),
          dz = (b.z() - a.z()) / Math.max(.001f, span);
      if (!forward) {
        dx = -dx;
        dz = -dz;
      }
      float lane = width * .24f;
      return new Pose(
          a.x() + (b.x() - a.x()) * t - dz * lane,
          a.z() + (b.z() - a.z()) * t + dx * lane,
          (float) Math.toDegrees(Math.atan2(dz, dx)));
    }
  }

  private record Cut(float distance, Node node) {}

  final List<Edge> edges = new ArrayList<>();

  StreetNetwork(Plan plan) {
    Map<String, Node> nodes = new LinkedHashMap<>();
    for (var road : plan.roads())
      if (road.points().size() > 1)
        for (Point p : List.of(road.points().getFirst(), road.points().getLast()))
          nodes.computeIfAbsent(
              Math.round(p.x() * 100) + ":" + Math.round(p.z() * 100), k -> new Node(p));
    Map<Long, List<Node>> buckets = new HashMap<>();
    for (var node : nodes.values())
      buckets
          .computeIfAbsent(bucket(node.point.x(), node.point.z()), k -> new ArrayList<>())
          .add(node);
    for (var road : plan.roads()) {
      var points = road.points();
      if (points.size() < 2) continue;
      float[] distances = new float[points.size()];
      for (int i = 1; i < points.size(); i++)
        distances[i] = distances[i - 1] + distance(points.get(i - 1), points.get(i));
      Map<Node, Float> cuts = new HashMap<>();
      for (int i = 1; i < points.size(); i++) {
        Point a = points.get(i - 1), b = points.get(i);
        float dx = b.x() - a.x(), dz = b.z() - a.z(), l2 = dx * dx + dz * dz;
        if (l2 < .001f) continue;
        for (int x = (int) Math.floor((Math.min(a.x(), b.x()) - 1.5) / 64);
            x <= (int) Math.floor((Math.max(a.x(), b.x()) + 1.5) / 64);
            x++)
          for (int z = (int) Math.floor((Math.min(a.z(), b.z()) - 1.5) / 64);
              z <= (int) Math.floor((Math.max(a.z(), b.z()) + 1.5) / 64);
              z++)
            for (var node : buckets.getOrDefault(key(x, z), List.of())) {
              float t = ((node.point.x() - a.x()) * dx + (node.point.z() - a.z()) * dz) / l2;
              if (t < -.001 || t > 1.001) continue;
              t = Math.max(0, Math.min(1, t));
              if (Math.hypot(node.point.x() - a.x() - dx * t, node.point.z() - a.z() - dz * t)
                  < 1.5) cuts.putIfAbsent(node, distances[i - 1] + (float) Math.sqrt(l2) * t);
            }
      }
      var sorted =
          cuts.entrySet().stream()
              .map(e -> new Cut(e.getValue(), e.getKey()))
              .sorted(Comparator.comparingDouble(Cut::distance))
              .toList();
      for (int i = 1; i < sorted.size(); i++) {
        Cut a = sorted.get(i - 1), b = sorted.get(i);
        if (b.distance - a.distance < 3 || a.node == b.node) continue;
        List<Point> segment = new ArrayList<>();
        segment.add(a.node.point);
        for (int j = 1; j < points.size() - 1; j++)
          if (distances[j] > a.distance + .01f && distances[j] < b.distance - .01f)
            segment.add(points.get(j));
        segment.add(b.node.point);
        Edge edge =
            new Edge(
                edges.size(), a.node, b.node, List.copyOf(segment), road.width(), road.avenue());
        edges.add(edge);
        a.node.edges.add(edge);
        b.node.edges.add(edge);
      }
    }
  }

  private static float distance(Point a, Point b) {
    return (float) Math.hypot(a.x() - b.x(), a.z() - b.z());
  }

  private static long bucket(float x, float z) {
    return key((int) Math.floor(x / 64), (int) Math.floor(z / 64));
  }

  private static long key(int x, int z) {
    return ((long) x << 32) ^ (z & 0xffffffffL);
  }

  static final class Vehicle {
    private Edge edge, next;
    private boolean forward, nextForward;
    private float position, turnProgress, turnLength;
    private Pose turnStart, turnEnd;
    private float c1x, c1z, c2x, c2z;
    private final SplittableRandom random;

    Vehicle(Edge edge, boolean forward, float fraction, long seed) {
      this.edge = edge;
      this.forward = forward;
      position = edge.usable(forward) * fraction;
      random = new SplittableRandom(seed);
    }

    int edgeId() {
      return edge.id;
    }

    boolean turning() {
      return next != null;
    }

    void advance(float dt) {
      float remaining = Math.max(0, Math.min(.1f, dt));
      while (remaining > 0) {
        if (next != null) {
          float seconds = (1 - turnProgress) * turnLength / Math.min(edge.speed, next.speed);
          if (remaining < seconds) {
            turnProgress += remaining * Math.min(edge.speed, next.speed) / turnLength;
            return;
          }
          remaining -= seconds;
          edge = next;
          forward = nextForward;
          next = null;
          position = 0;
        } else {
          float seconds = (edge.usable(forward) - position) / edge.speed;
          if (remaining < seconds) {
            position += remaining * edge.speed;
            return;
          }
          remaining -= seconds;
          position = edge.usable(forward);
          beginTurn();
        }
      }
    }

    private void beginTurn() {
      Node node = forward ? edge.to : edge.from;
      List<Edge> choices = node.edges.stream().filter(e -> e != edge).toList();
      next = choices.isEmpty() ? edge : choices.get(random.nextInt(choices.size()));
      nextForward = next.from == node;
      turnStart = edge.pose(edge.length - edge.trim(forward, false), forward);
      turnEnd = next.pose(next.trim(nextForward, true), nextForward);
      float handle =
          Math.max(2, (float) Math.hypot(turnEnd.x - turnStart.x, turnEnd.z - turnStart.z) * .48f);
      c1x = turnStart.x + (float) Math.cos(Math.toRadians(turnStart.angle)) * handle;
      c1z = turnStart.z + (float) Math.sin(Math.toRadians(turnStart.angle)) * handle;
      c2x = turnEnd.x - (float) Math.cos(Math.toRadians(turnEnd.angle)) * handle;
      c2z = turnEnd.z - (float) Math.sin(Math.toRadians(turnEnd.angle)) * handle;
      turnProgress = 0;
      turnLength = 0;
      Pose prev = curve(0);
      for (int i = 1; i <= 12; i++) {
        Pose p = curve(i / 12f);
        turnLength += (float) Math.hypot(p.x - prev.x, p.z - prev.z);
        prev = p;
      }
      turnLength = Math.max(.1f, turnLength);
    }

    private Pose curve(float t) {
      float s = 1 - t;
      float x =
          s * s * s * turnStart.x
              + 3 * s * s * t * c1x
              + 3 * s * t * t * c2x
              + t * t * t * turnEnd.x;
      float z =
          s * s * s * turnStart.z
              + 3 * s * s * t * c1z
              + 3 * s * t * t * c2z
              + t * t * t * turnEnd.z;
      float dx =
          3 * s * s * (c1x - turnStart.x) + 6 * s * t * (c2x - c1x) + 3 * t * t * (turnEnd.x - c2x);
      float dz =
          3 * s * s * (c1z - turnStart.z) + 6 * s * t * (c2z - c1z) + 3 * t * t * (turnEnd.z - c2z);
      return new Pose(x, z, (float) Math.toDegrees(Math.atan2(dz, dx)));
    }

    Pose pose() {
      return next == null
          ? edge.pose(edge.trim(forward, true) + position, forward)
          : curve(turnProgress);
    }
  }
}
