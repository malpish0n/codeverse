package dev.codeverse.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.codeverse.model.CityGenerator.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class StreetNetworkTest {
  private StreetNetwork junction() {
    return new StreetNetwork(
        new Plan(
            List.of(),
            List.of(
                new Road(List.of(new Point(-100, 0), new Point(100, 0)), 14, false),
                new Road(List.of(new Point(0, 0), new Point(0, 100)), 14, false))));
  }

  @Test
  void generatedCitySupportsLongTripsAcrossManyStreets() {
    var region = new dev.codeverse.model.CityGenerator(Settings.defaults()).region(0);
    var network = new StreetNetwork(new Plan(region.blocks(), region.roads()));
    var visited = new HashSet<Integer>();
    var car = new StreetNetwork.Vehicle(network.edges.getFirst(), true, .5f, 19);
    for (int frame = 0; frame < 36000; frame++) {
      car.advance(1 / 30f);
      visited.add(car.edgeId());
    }
    assertTrue(visited.size() > 15, "Traffic must travel through the city: " + visited.size());
  }

  @Test
  void splitsJunctionAndExitsOntoAnotherStreetWithoutTeleporting() {
    var network = junction();
    assertEquals(3, network.edges.size());
    for (int seed = 0; seed < 20; seed++) {
      var car = new StreetNetwork.Vehicle(network.edges.getFirst(), true, .9f, seed);
      int initial = car.edgeId();
      var prev = car.pose();
      boolean changed = false;
      for (int i = 0; i < 600; i++) {
        car.advance(1 / 60f);
        var p = car.pose();
        assertTrue(Math.hypot(p.x() - prev.x(), p.z() - prev.z()) < .5);
        assertTrue(Float.isFinite(p.angle()));
        prev = p;
        if (car.edgeId() != initial) {
          changed = true;
          break;
        }
      }
      assertTrue(changed, "Must leave the incoming street at a junction");
    }
  }

  @Test
  void deterministicRoutesAndDeadEndTurns() {
    var network =
        new StreetNetwork(
            new Plan(
                List.of(),
                List.of(new Road(List.of(new Point(0, 0), new Point(100, 0)), 14, false))));
    var a = new StreetNetwork.Vehicle(network.edges.getFirst(), true, .95f, 42);
    var b = new StreetNetwork.Vehicle(network.edges.getFirst(), true, .95f, 42);
    boolean reversed = false;
    for (int i = 0; i < 1800; i++) {
      a.advance(1 / 60f);
      b.advance(1 / 60f);
      assertEquals(a.pose(), b.pose());
      if (a.pose().z() < 0) reversed = true;
    }
    assertTrue(reversed);
  }
}
