package dev.codeverse.render;

import com.badlogic.gdx.*;
import com.badlogic.gdx.graphics.*;
import com.badlogic.gdx.graphics.glutils.HdpiUtils;
import com.badlogic.gdx.math.*;
import com.badlogic.gdx.math.collision.*;
import dev.codeverse.model.*;
import dev.codeverse.model.Data.*;
import java.util.*;

public final class CityRenderer implements AutoCloseable {
  public final PerspectiveCamera camera = new PerspectiveCamera(48, 1280, 800);
  private final CityMesh mesh = new CityMesh();
  private final CityTraffic traffic = new CityTraffic();
  private double lifeTime;
  public boolean living = true;
  private CityGenerator.Plan plan = CityGenerator.Plan.empty();

  public void setPlan(CityGenerator.Plan plan) {
    if (this.plan != plan) {
      this.plan = plan;
      traffic.plan(plan);
      dirty = true;
    }
  }

  private final Map<String, Building> buildings = new LinkedHashMap<>();
  private final Vector3 center = new Vector3(), target = new Vector3(), look = new Vector3();
  private final Vector3 position = new Vector3(),
      forwardVector = new Vector3(),
      rightVector = new Vector3();
  private boolean dirty = true, previousHeat, cameraReady;
  private float transition = 1, explode, sceneRadius = 500;
  private float tourTime;
  private int tourStop = -1;
  private List<Building> tourPoints = List.of();
  public float yaw = 42, pitch = 44, distance = 900, spacing = 85;
  public boolean exploded, tour, reducedMotion, heat;
  private String selected, isolated;
  private Set<String> highlight = Set.of();
  private static final Color ASPHALT = Color.valueOf("202C35"),
      PAVEMENT = Color.valueOf("687982"),
      ROOF = Color.valueOf("91A7AD"),
      GREEN = Color.valueOf("3F816C"),
      TRUNK = Color.valueOf("61534A"),
      MARKING = Color.valueOf("C3B685");
  private static final Color[] FACADES = {
    Color.valueOf("7093A2"),
    Color.valueOf("ACAA99"),
    Color.valueOf("8F8F9A"),
    Color.valueOf("728D8B"),
    Color.valueOf("A68C7B")
  };

  public static final class Building {
    public FileRevision file;
    public dev.codeverse.model.BuildingProfile profile;
    public Cell cell;
    public float height, goal, from;
    public int renderId;
    public boolean retiring;
    public String change = "";
    public double hotspot;
    public final BoundingBox bounds = new BoundingBox();
    public final BoundingBox localBounds = new BoundingBox();
    public final Matrix4 inverse = new Matrix4();

    Building(FileRevision f, Cell c) {
      file = f;
      cell = c;
      goal = Data.height(f.loc());
    }
  }

  public CityRenderer() {
    camera.near = 1;
    camera.far = 30000;
  }

  public void setScene(
      Snapshot snap,
      Map<String, Cell> cells,
      List<Change> changes,
      Map<String, Hotspot> hotspots,
      boolean reset) {
    if (reset) buildings.clear();
    // Begin new transitions from the currently displayed height, including interrupted playback.
    buildings.entrySet().removeIf(e -> e.getValue().retiring && e.getValue().height < .01f);
    for (var b : buildings.values()) b.from = b.height;
    Set<String> live = new HashSet<>();
    Map<String, String> diff = new HashMap<>();
    for (var d : changes) diff.put(d.type().equals("DELETE") ? d.oldPath() : d.newPath(), d.type());
    for (var f : snap.files()) {
      live.add(f.path());
      var b = buildings.computeIfAbsent(f.path(), p -> new Building(f, cells.get(p)));
      b.retiring = false;
      b.file = f;
      b.profile = dev.codeverse.model.BuildingProfile.analyze(f);
      b.cell = cells.get(f.path());
      b.inverse
          .setToTranslation(b.cell.x(), 0, b.cell.z())
          .rotate(Vector3.Y, -b.cell.angle())
          .inv();
      b.goal = Data.height(f.loc());
      b.change = diff.getOrDefault(f.path(), "");
      b.hotspot = hotspots.getOrDefault(f.path(), new Hotspot(0, 0, 0, 0)).score();
    }
    for (var b : buildings.values())
      if (!live.contains(b.file.path())) {
        b.retiring = true;
        b.goal = 0;
        b.change = "DELETE";
      }
    BoundingBox bounds = new BoundingBox();
    for (var b : buildings.values()) bounds.ext(b.cell.x(), 0, b.cell.z());
    for (var block : plan.blocks()) for (var p : block.outline()) bounds.ext(p.x(), 0, p.z());
    if (!buildings.isEmpty()) {
      bounds.getCenter(center);
      Vector3 size = new Vector3();
      bounds.getDimensions(size);
      sceneRadius = Math.max(160, Math.max(size.x, size.z) * .65f);
    }
    tourPoints =
        buildings.values().stream()
            .filter(b -> !b.retiring)
            .sorted(
                Comparator.comparingDouble((Building b) -> -b.hotspot)
                    .thenComparing(b -> b.file.path()))
            .limit(5)
            .toList();
    transition = 0;
    dirty = true;
    if (reset) reset();
  }

  private void rebuild() {
    mesh.clear();
    int renderId = 0;
    drawPlan();
    for (var b : buildings.values()) {
      b.renderId = renderId++;
      mesh.building(-1);
      mesh.transform(b.cell.x(), b.cell.z(), b.cell.angle());
      float x = 0, z = 0;
      int layer = b.cell.layer();
      fixed(x, -.7f, z, b.cell.width() + 4, .7f, b.cell.depth() + 4, layer, PAVEMENT);
      int seed = b.file.path().hashCode() & 0x7fffffff;
      float width = b.cell.width() * (.72f + (seed % 4) * .06f),
          depth = b.cell.depth() * (.75f + (seed % 3) * .07f);
      Color facade = color(b, seed);
      mesh.building(b.renderId);
      // Every section shares the GPU height transition and stays inside its parcel.
      switch (b.profile.model()) {
        case HOUSE -> {
          section(b, 0, 0, width * .78f, depth * .75f, 0, .72f, facade, true);
          section(b, 0, 0, width * .88f, depth * .84f, .72f, .80f, ROOF, false);
          section(b, 0, 0, width * .58f, depth * .72f, .80f, .90f, ROOF, false);
          section(b, 0, 0, width * .28f, depth * .62f, .90f, 1, ROOF, false);
        }
        case BLOCK -> {
          section(b, 0, 0, width, depth * .90f, 0, .92f, facade, true);
          section(b, 0, 0, width, depth * .94f, .92f, 1, ROOF, false);
        }
        case COURTYARD -> {
          section(b, -width * .34f, 0, width * .30f, depth, 0, 1, facade, true);
          section(b, width * .34f, 0, width * .30f, depth, 0, .82f, facade, true);
          section(b, 0, depth * .34f, width * .38f, depth * .32f, 0, .70f, facade, true);
        }
        case TERRACES -> {
          section(b, 0, 0, width, depth, 0, .34f, facade, true);
          section(
              b, -width * .10f, depth * .10f, width * .78f, depth * .78f, .34f, .65f, facade, true);
          section(
              b, -width * .20f, depth * .20f, width * .54f, depth * .54f, .65f, 1, facade, true);
          section(
              b,
              width * .40f,
              0,
              width * .12f,
              depth * .85f,
              .34f,
              .36f,
              Color.valueOf("50866B"),
              false);
          section(
              b,
              width * .22f,
              depth * .10f,
              width * .12f,
              depth * .62f,
              .65f,
              .67f,
              Color.valueOf("50866B"),
              false);
        }
        case TOWER -> {
          section(b, 0, 0, width, depth, 0, .17f, facade, true);
          section(b, 0, 0, width * .62f, depth * .65f, .17f, .88f, facade, true);
          section(b, 0, 0, width * .45f, depth * .48f, .88f, .96f, ROOF, false);
          section(b, 0, 0, width * .14f, depth * .16f, .96f, 1, ROOF, false);
        }
      }
    }
    mesh.transform(0, 0, 0);
    mesh.finish();
    dirty = false;
  }

  private void section(
      Building b,
      float x,
      float z,
      float width,
      float depth,
      float bottom,
      float top,
      Color color,
      boolean windows) {
    mesh.box(
        x,
        b.goal * bottom,
        z,
        width,
        b.goal * (top - bottom),
        depth,
        b.from * bottom,
        b.from * (top - bottom),
        b.cell.layer(),
        color,
        windows);
  }

  private void drawPlan() {
    mesh.transform(0, 0, 0);
    mesh.building(-1);
    for (var block : plan.blocks()) {
      mesh.surface(block.outline(), -1.4f, 0, Color.valueOf("334548"));
      if (block.park()) {
        float cx = 0, cz = 0;
        for (var p : block.outline()) {
          cx += p.x() / 4;
          cz += p.z() / 4;
        }
        List<CityGenerator.Point> inset = new ArrayList<>();
        for (var p : block.outline())
          inset.add(new CityGenerator.Point(cx + (p.x() - cx) * .86f, cz + (p.z() - cz) * .86f));
        mesh.surface(inset, -.9f, 0, GREEN);
      }
      if (block.park()) {
        var corners = block.outline();
        var a = corners.get(0);
        var b = corners.get(1);
        var c = corners.get(2);
        var d = corners.get(3);
        float cx = (a.x() + b.x() + c.x() + d.x()) / 4, cz = (a.z() + b.z() + c.z() + d.z()) / 4;
        parkPath(
            (a.x() + b.x()) / 2, (a.z() + b.z()) / 2, (c.x() + d.x()) / 2, (c.z() + d.z()) / 2);
        parkPath(
            (a.x() + d.x()) / 2, (a.z() + d.z()) / 2, (b.x() + c.x()) / 2, (b.z() + c.z()) / 2);
        mesh.transform(cx, cz, 0);
        fixed(0, -.75f, 0, 9, .7f, 9, 0, ROOF);
        fixed(0, -.02f, 0, 7, .08f, 7, 0, Color.valueOf("4FA9BD"));
        fixed(0, 0, 0, 1.6f, 2, 1.6f, 0, ROOF);
        for (float side : new float[] {-1, 1}) {
          fixed(side * 12, 0, 5, 6, .65f, 2, 0, TRUNK);
          fixed(side * 12, .65f, 5.8f, 6, 1.6f, .45f, 0, TRUNK);
          fixed(side * 12, -.8f, 5, .5f, .8f, 1.7f, 0, ROOF);
        }
        mesh.transform(0, 0, 0);
      }
      for (var p : block.trees()) {
        int treeSeed = Float.floatToIntBits(p.x()) ^ Float.floatToIntBits(p.z());
        float size = .85f + Math.floorMod(treeSeed, 7) * .09f;
        Color foliage =
            new Color(GREEN).lerp(Color.valueOf("78A75C"), Math.floorMod(treeSeed, 5) * .13f);
        fixed(p.x(), -.8f, p.z(), 1.3f, 6 * size, 1.3f, 0, TRUNK);
        fixed(p.x(), 3 * size, p.z(), 7 * size, 6 * size, 7 * size, 0, foliage);
        fixed(p.x(), 8 * size, p.z(), 4.5f * size, 3 * size, 4.5f * size, 0, foliage);
      }
    }
    for (var road : plan.roads()) {
      float travelled = 0;
      for (int i = 1; i < road.points().size(); i++) {
        var a = road.points().get(i - 1);
        var b = road.points().get(i);
        float dx = b.x() - a.x(), dz = b.z() - a.z(), length = (float) Math.hypot(dx, dz);
        mesh.transform(
            (a.x() + b.x()) / 2, (a.z() + b.z()) / 2, (float) Math.toDegrees(Math.atan2(dz, dx)));
        // Slight overlap at bends closes joins; asphalt is above the sidewalk foundation.
        fixed(0, -1.5f, 0, length + .8f, .7f, road.width() + 5, 0, PAVEMENT);
        fixed(0, -.75f, 0, length + .9f, .15f, road.width(), 0, ASPHALT);
        if (road.avenue()) {
          for (float dash = 8 - (travelled % 18); dash < length; dash += 18)
            if (dash >= 0)
              fixed(
                  dash - length / 2, -.58f, 0, Math.min(6, length - dash), .04f, .45f, 0, MARKING);
        }
        if (i == 1 && !road.avenue()) {
          for (float stripe = -road.width() / 2 + 1; stripe < road.width() / 2 - 1; stripe += 3)
            fixed(length * .15f, -.57f, stripe, 4, .04f, 1.4f, 0, ROOF);
        }
        if (road.avenue() && i % 4 == 2) {
          float curb = road.width() / 2 + 1.5f;
          fixed(0, -.7f, curb, .6f, 9, .6f, 0, ROOF);
          fixed(0, 8.3f, curb - 1.2f, .5f, .5f, 3, 0, ROOF);
          fixed(0, 8.1f, curb - 2.3f, 1.1f, .35f, 1.3f, 0, Color.valueOf("FFE7A0"));
        }
        travelled += length;
      }
    }
    mesh.transform(0, 0, 0);
  }

  private void parkPath(float ax, float az, float bx, float bz) {
    float length = (float) Math.hypot(bx - ax, bz - az);
    mesh.transform(
        (ax + bx) / 2, (az + bz) / 2, (float) Math.toDegrees(Math.atan2(bz - az, bx - ax)));
    fixed(0, -.85f, 0, length * .78f, .12f, 4, 0, Color.valueOf("BAAC8A"));
    mesh.transform(0, 0, 0);
  }

  private void fixed(float x, float y, float z, float w, float h, float d, int layer, Color color) {
    mesh.box(x, y, z, w, h, d, y, h, layer, color, false);
  }

  private Color color(Building b, int seed) {
    Color c = FACADES[seed % FACADES.length].cpy();
    if (b.change.equals("ADD")) c.set(Color.valueOf("59C8BE"));
    else if (b.change.equals("DELETE")) c.set(Color.valueOf("EC7885"));
    else if (!b.change.isEmpty()) c.set(Color.valueOf("A99BE3"));
    if (heat) c.set(Color.valueOf("426575")).lerp(Color.valueOf("FFCC78"), (float) b.hotspot);
    if (isolated != null && !isolated.equals(b.file.module())) c.mul(.25f);
    if (highlight.contains(b.file.path())) c.set(Color.valueOf("B29BFF"));
    return c;
  }

  public void reset() {
    float lift =
        exploded
            ? buildings.values().stream().mapToInt(b -> b.cell.layer()).max().orElse(0) * spacing
            : 0;
    target.set(center).add(0, lift / 2, 0);
    distance = sceneRadius * 2.4f + 200 + lift * .8f;
    yaw = 42;
    pitch = 44;
  }

  public void focus(String path) {
    var b = buildings.get(path);
    if (b == null) return;
    target.set(b.cell.x(), b.goal / 2 + b.cell.layer() * spacing * explode, b.cell.z());
    distance = 230;
    select(path);
  }

  public void select(String path) {
    selected = path;
  }

  public void highlight(Set<String> paths) {
    if (!highlight.equals(paths)) dirty = true;
    highlight = Set.copyOf(paths);
  }

  public void isolate(String module) {
    if (!Objects.equals(isolated, module)) dirty = true;
    isolated = module;
  }

  public Collection<Building> buildings() {
    return buildings.values();
  }

  public Building selected() {
    return buildings.get(selected);
  }

  public Vector3 target() {
    return target;
  }

  public void orbit(float dx, float dy) {
    tour = false;
    yaw = Motion.orbitYaw(yaw, dx);
    pitch = Motion.orbitPitch(pitch, dy);
  }

  public void zoom(float amount) {
    tour = false;
    distance = Motion.zoomDistance(distance, amount);
  }

  public void pan(float dx, float dy) {
    tour = false;
    float scale =
        2
            * distance
            * MathUtils.tan(camera.fieldOfView * MathUtils.degreesToRadians / 2)
            / Math.max(1, camera.viewportHeight);
    rightVector.set(camera.direction).crs(camera.up).nor();
    target.mulAdd(rightVector, -dx * scale).mulAdd(camera.up, dy * scale);
  }

  public void move(float forward, float right, float dt, boolean boost) {
    if (forward == 0 && right == 0) return;
    tour = false;
    float speed = (boost ? 3 : 1) * Math.max(80, distance * .6f) * dt;
    forwardVector.set(camera.direction.x, 0, camera.direction.z).nor();
    rightVector.set(forwardVector).crs(Vector3.Y);
    target.mulAdd(forwardVector, forward * speed).mulAdd(rightVector, right * speed);
  }

  public void invalidate() {
    dirty = true;
  }

  public void render(float dt, int left, int bottom, int width, int height) {
    dt = MathUtils.clamp(dt, 0, .1f);
    if (living && !reducedMotion) lifeTime += dt;
    if (previousHeat != heat) {
      dirty = true;
      previousHeat = heat;
    }
    float blend = reducedMotion ? 1 : Motion.blend(7, dt);
    explode = MathUtils.lerp(explode, exploded ? 1 : 0, blend);
    transition = reducedMotion ? 1 : Math.min(1, transition + dt / 0.65f);
    float progress = Interpolation.smoother.apply(transition);
    if (tour && !reducedMotion) {
      tourTime += dt;
      int stop = (int) (tourTime / 5);
      if (stop != tourStop && !tourPoints.isEmpty()) {
        tourStop = stop;
        var b = tourPoints.get(stop % tourPoints.size());
        target.set(b.cell.x(), b.goal * .6f + b.cell.layer() * spacing * explode, b.cell.z());
        distance = 280;
        pitch = 28;
      }
      yaw += dt * 11;
    } else {
      tourTime = 0;
      tourStop = -1;
    }
    float yr = yaw * MathUtils.degreesToRadians, pr = pitch * MathUtils.degreesToRadians;
    position.set(
        target.x + distance * MathUtils.cos(pr) * MathUtils.cos(yr),
        target.y + distance * MathUtils.sin(pr),
        target.z + distance * MathUtils.cos(pr) * MathUtils.sin(yr));
    float cameraBlend = reducedMotion || !cameraReady ? 1 : Motion.blend(6, dt);
    camera.position.lerp(position, cameraBlend);
    look.lerp(target, cameraBlend);
    cameraReady = true;
    for (var b : buildings.values()) {
      b.height = MathUtils.lerp(b.from, b.goal, progress);
      float y = b.cell.layer() * spacing * explode;
      float radians = b.cell.angle() * MathUtils.degreesToRadians;
      float c = Math.abs(MathUtils.cos(radians)), s = Math.abs(MathUtils.sin(radians));
      float w = b.cell.width() * c + b.cell.depth() * s,
          d = b.cell.width() * s + b.cell.depth() * c;
      b.bounds.min.set(b.cell.x() - w / 2, y, b.cell.z() - d / 2);
      b.bounds.max.set(b.cell.x() + w / 2, y + b.height + 12, b.cell.z() + d / 2);
      b.bounds.update();
      b.localBounds.min.set(-b.cell.width() / 2, y, -b.cell.depth() / 2);
      b.localBounds.max.set(b.cell.width() / 2, y + b.height + 12, b.cell.depth() / 2);
      b.localBounds.update();
      if (!exploded
          && explode < .001f
          && Math.abs(camera.position.x - b.cell.x()) < w / 2 + 5
          && Math.abs(camera.position.z - b.cell.z()) < d / 2 + 5)
        camera.position.y = Math.max(camera.position.y, y + b.height + 15);
    }
    // A scale-aware near plane preserves depth precision across the expanded city.
    float viewingDistance = camera.position.dst(look);
    camera.near = Math.max(.5f, viewingDistance * .015f);
    camera.far = Math.max(5000, sceneRadius * 5 + viewingDistance * 3);
    camera.viewportWidth = width;
    camera.viewportHeight = height;
    camera.up.set(Vector3.Y);
    camera.lookAt(look);
    camera.update();
    HdpiUtils.glViewport(left, bottom, width, height);
    Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
    Gdx.gl.glDepthMask(true);
    Gdx.gl.glEnable(GL20.GL_CULL_FACE);
    Gdx.gl.glCullFace(GL20.GL_BACK);
    if (dirty) rebuild();
    mesh.render(
        camera,
        progress,
        spacing * explode,
        selected() == null ? -1 : selected().renderId,
        (float) lifeTime);
    traffic.render(camera, lifeTime);
    Gdx.gl.glDisable(GL20.GL_CULL_FACE);
    HdpiUtils.glViewport(0, 0, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
  }

  public String pick(int x, int y, int left, int bottom, int width, int height) {
    Ray ray = camera.getPickRay(x, y, left, bottom, width, height);
    float nearest = Float.MAX_VALUE;
    String found = null;
    Vector3 hit = new Vector3();
    for (var b : buildings.values())
      if (!b.retiring && Intersector.intersectRayBounds(ray, b.bounds, hit)) {
        Ray localRay = ray.cpy().mul(b.inverse);
        if (!Intersector.intersectRayBounds(localRay, b.localBounds, hit)) continue;
        float d = hit.dst2(localRay.origin);
        if (d < nearest) {
          nearest = d;
          found = b.file.path();
        }
      }
    return found;
  }

  public void close() {
    mesh.close();
    traffic.close();
  }
}
