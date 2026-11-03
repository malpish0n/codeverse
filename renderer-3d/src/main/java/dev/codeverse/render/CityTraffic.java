package dev.codeverse.render;

import com.badlogic.gdx.graphics.*;
import com.badlogic.gdx.math.*;
import dev.codeverse.model.CityGenerator.*;
import java.util.*;

final class CityTraffic implements AutoCloseable {
  private record Car(StreetNetwork.Vehicle vehicle, Color color) {}

  private final List<Car> cars = new ArrayList<>();
  private final CityMesh mesh = new CityMesh();
  private final Matrix4 transform = new Matrix4();
  private final Color[] colors = {
    Color.valueOf("C75B50"),
    Color.valueOf("E7C36A"),
    Color.valueOf("709FC0"),
    Color.valueOf("E1E4DF"),
    Color.valueOf("649989")
  };

  private double lastTime = Double.NaN;
  private float carWidth;

  CityTraffic() {
    box(0, .1f, 0, 2.35f, .43f, 1, Color.WHITE);
    box(-.1f, .53f, 0, 1.15f, .42f, .86f, Color.valueOf("526B7C"));
    for (float x : new float[] {-.73f, .73f})
      for (float z : new float[] {-.51f, .51f})
        box(x, 0, z, .40f, .34f, .16f, Color.valueOf("20272D"));
    for (float z : new float[] {-.32f, .32f}) {
      box(1.18f, .23f, z, .06f, .18f, .2f, Color.valueOf("FFFFCB"));
      box(-1.18f, .23f, z, .06f, .18f, .2f, Color.valueOf("FF5142"));
    }
    mesh.finish();
  }

  private void box(float x, float y, float z, float w, float h, float d, Color c) {
    mesh.box(x, y, z, w, h, d, y, h, 0, c, false);
  }

  void plan(Plan plan) {
    cars.clear();
    lastTime = Double.NaN;
    var network = new StreetNetwork(plan);
    carWidth =
        (float)
            Math.min(3.6, network.edges.stream().mapToDouble(e -> e.width * .23).min().orElse(2));
    var streets = new ArrayList<StreetNetwork.Edge>();
    int streetCount = Math.min(600, network.edges.size());
    for (int i = 0; i < streetCount; i++)
      streets.add(network.edges.get(i * network.edges.size() / streetCount));
    int[] counts = new int[streets.size()];
    int total = 0;
    for (int pass = 0; pass < 18 && total < 1200; pass++)
      for (int i = 0; i < counts.length && total < 1200; i++) {
        int wanted = Math.min(18, Math.max(2, (int) (streets.get(i).length / 22)));
        if (pass < wanted) {
          counts[i]++;
          total++;
        }
      }
    for (int i = 0; i < counts.length; i++)
      for (int n = 0; n < counts[i]; n++)
        cars.add(
            new Car(
                new StreetNetwork.Vehicle(
                    streets.get(i), n % 2 == 0, (n + .37f) / counts[i], i * 7919L + n),
                colors[(i + n) % colors.length]));
  }

  void render(Camera camera, double time) {
    float dt = Double.isNaN(lastTime) ? 0 : (float) Math.max(0, time - lastTime);
    lastTime = time;
    for (var car : cars) {
      car.vehicle.advance(dt);
      var p = car.vehicle.pose();
      if (!camera.frustum.sphereInFrustum(p.x(), 2, p.z(), carWidth * 2)) continue;
      transform
          .setToTranslation(p.x(), -.52f, p.z())
          .rotate(Vector3.Y, -p.angle())
          .scale(carWidth, carWidth, carWidth);
      mesh.render(camera, 1, 0, -1, 0, transform, car.color);
    }
  }

  public void close() {
    mesh.close();
  }
}
