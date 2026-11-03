package dev.codeverse.render;

import com.badlogic.gdx.graphics.*;
import com.badlogic.gdx.math.*;
import dev.codeverse.model.CityGenerator.*;
import java.util.*;

final class CityTraffic implements AutoCloseable {
  private record Car(TrafficRoute route, float offset, Color color) {}

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
    // Round-robin road sampling distributes a bounded traffic budget over the whole city.
    List<TrafficRoute> routes =
        plan.roads().stream()
            .filter(r -> r.points().size() > 1)
            .map(TrafficRoute::new)
            .filter(r -> r.length > 30)
            .toList();
    for (int pass = 0; pass < 5 && cars.size() < 240; pass++)
      for (int i = 0; i < routes.size() && cars.size() < 240; i++) {
        var route = routes.get(i);
        int count = Math.min(5, Math.max(1, (int) (route.length / 100)));
        if (pass < count)
          cars.add(
              new Car(
                  route, route.length * (pass + .37f) / count, colors[(i + pass) % colors.length]));
      }
  }

  void render(Camera camera, double time) {
    for (var car : cars) {
      var p = car.route.at(car.offset + time * car.route.speed);
      transform
          .setToTranslation(p.x(), -.52f, p.z())
          .rotate(Vector3.Y, -p.angle())
          .scale(car.route.carWidth, car.route.carWidth, car.route.carWidth);
      mesh.render(camera, 1, 0, -1, 0, transform, car.color);
    }
  }

  public void close() {
    mesh.close();
  }
}
