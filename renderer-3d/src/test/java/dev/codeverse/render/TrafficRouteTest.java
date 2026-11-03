package dev.codeverse.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.codeverse.model.CityGenerator.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class TrafficRouteTest {
  @Test
  void routeWrapsContinuouslyWithoutTeleportation() {
    var route =
        new TrafficRoute(
            new Road(List.of(new Point(0, 0), new Point(100, 0), new Point(200, 10)), 22, true));
    var a = route.at(0);
    var b = route.at(route.length);
    var c = route.at(route.length - .01);
    assertEquals(a, b);
    assertTrue(Math.hypot(a.x() - c.x(), a.z() - c.z()) < .02);
    for (double d = 0; d < route.length; d += .5) {
      a = route.at(d);
      b = route.at(d + .5);
      assertTrue(Math.hypot(a.x() - b.x(), a.z() - b.z()) <= .501);
      assertTrue(Float.isFinite(a.angle()));
    }
  }

  @Test
  void carsFitBothLanesAndTravelInOppositeDirections() {
    var route = new TrafficRoute(new Road(List.of(new Point(0, 0), new Point(200, 0)), 8, false));
    var outbound = route.at(50);
    var inbound = route.at(route.length / 2 + 50);
    assertTrue(outbound.z() > 0);
    assertTrue(inbound.z() < 0);
    assertTrue(Math.abs(outbound.z()) + route.carWidth / 2 < 4);
    assertEquals(0, outbound.angle(), .01);
    assertEquals(180, Math.abs(inbound.angle()), .01);
  }
}
