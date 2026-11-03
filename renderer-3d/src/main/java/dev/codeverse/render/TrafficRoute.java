package dev.codeverse.render;

import dev.codeverse.model.CityGenerator.*;
import java.util.*;

/** Closed two-lane route with rounded turnarounds: no endpoint teleportation. */
final class TrafficRoute {
  record Pose(float x, float z, float angle) {}

  private final List<Point> points = new ArrayList<>();
  private final float[] cumulative;
  final float length, carWidth, speed;

  TrafficRoute(Road road) {
    var center = road.points();
    float lane = road.width() * .24f;
    carWidth = Math.min(3.6f, road.width() * .23f);
    speed = road.avenue() ? 16 : 10;
    for (int i = 0; i < center.size(); i++) points.add(offset(center, i, lane));
    turn(center.getLast(), tangent(center, center.size() - 1), lane, true);
    for (int i = center.size() - 1; i >= 0; i--) points.add(offset(center, i, -lane));
    turn(center.getFirst(), tangent(center, 0), lane, false);
    points.add(points.getFirst());
    cumulative = new float[points.size()];
    for (int i = 1; i < points.size(); i++)
      cumulative[i] =
          cumulative[i - 1]
              + (float)
                  Math.hypot(
                      points.get(i).x() - points.get(i - 1).x(),
                      points.get(i).z() - points.get(i - 1).z());
    length = cumulative[cumulative.length - 1];
  }

  private Point tangent(List<Point> p, int i) {
    Point a = p.get(Math.max(0, i - 1)), b = p.get(Math.min(p.size() - 1, i + 1));
    float length = (float) Math.hypot(b.x() - a.x(), b.z() - a.z());
    return length == 0
        ? new Point(1, 0)
        : new Point((b.x() - a.x()) / length, (b.z() - a.z()) / length);
  }

  private Point offset(List<Point> p, int i, float lane) {
    Point t = tangent(p, i), c = p.get(i);
    return new Point(c.x() - t.z() * lane, c.z() + t.x() * lane);
  }

  private void turn(Point c, Point t, float lane, boolean end) {
    for (int i = 1; i < 12; i++) {
      double a = Math.PI * i / 12;
      float along = (float) Math.sin(a) * lane * (end ? 1 : -1);
      float normal = (float) Math.cos(a) * lane * (end ? 1 : -1);
      points.add(
          new Point(
              c.x() + t.x() * along - t.z() * normal, c.z() + t.z() * along + t.x() * normal));
    }
  }

  Pose at(double distance) {
    float d = (float) ((distance % length + length) % length);
    int index = Arrays.binarySearch(cumulative, d);
    if (index < 0) index = -index - 1;
    index = Math.max(1, Math.min(index, points.size() - 1));
    Point a = points.get(index - 1), b = points.get(index);
    float span = cumulative[index] - cumulative[index - 1];
    float t = span == 0 ? 0 : (d - cumulative[index - 1]) / span;
    return new Pose(
        a.x() + (b.x() - a.x()) * t,
        a.z() + (b.z() - a.z()) * t,
        (float) Math.toDegrees(Math.atan2(b.z() - a.z(), b.x() - a.x())));
  }
}
