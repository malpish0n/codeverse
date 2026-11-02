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
  private final Map<String, Building> buildings = new LinkedHashMap<>();
  private final Vector3 center = new Vector3(), target = new Vector3(), look = new Vector3();
  private final Vector3 position = new Vector3(),
      forwardVector = new Vector3(),
      rightVector = new Vector3();
  private boolean dirty = true, previousExploded, previousHeat, cameraReady;
  private float previousSpacing = 85, transition = 1, explode, sceneRadius = 500;
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
    public Cell cell;
    public float height, goal, from;
    public int renderId;
    public boolean retiring;
    public String change = "";
    public double hotspot;
    public final BoundingBox bounds = new BoundingBox();

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
      b.cell = cells.get(f.path());
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
    // Individual city lots avoid overlapping plates when a module spans multiple reserved sectors.
    for (var b : buildings.values()) {
      b.renderId = renderId++;
      mesh.building(-1);
      float x = b.cell.x(), z = b.cell.z();
      int layer = b.cell.layer();
      fixed(x, -2, z, 23, 2, 23, layer, ASPHALT);
      fixed(x, -.7f, z, 19.5f, .7f, 19.5f, layer, PAVEMENT);
      int seed = b.file.path().hashCode() & 0x7fffffff;
      float width = b.cell.width() * (.72f + (seed % 4) * .06f),
          depth = b.cell.width() * (.75f + (seed % 3) * .07f);
      Color facade = color(b, seed);
      mesh.building(b.renderId);
      // A podium, narrower tower and roof plant room create a varied, readable skyline.
      float podium = Math.min(12, b.goal), oldPodium = Math.min(12, b.from);
      mesh.box(x, 0, z, width, podium, depth, 0, oldPodium, layer, facade, true);
      if (b.goal > 12 || b.from > 12) {
        mesh.box(
            x,
            podium,
            z,
            width * .80f,
            Math.max(0, b.goal - podium),
            depth * .84f,
            oldPodium,
            Math.max(0, b.from - oldPodium),
            layer,
            facade,
            true);
      }
      float cap = b.goal > 0 ? 1.1f : 0, oldCap = b.from > 0 ? 1.1f : 0;
      mesh.box(x, b.goal, z, width * .84f, cap, depth * .88f, b.from, oldCap, layer, ROOF, false);
      if (seed % 3 == 0)
        mesh.box(
            x,
            b.goal + cap,
            z,
            width * .30f,
            cap * 3,
            depth * .36f,
            b.from + oldCap,
            oldCap * 3,
            layer,
            ROOF,
            false);
      if (seed % 7 == 0)
        mesh.box(
            x,
            b.goal + cap,
            z,
            .55f,
            cap * 9,
            .55f,
            b.from + oldCap,
            oldCap * 9,
            layer,
            ROOF,
            false);
      mesh.building(-1);
      fixed(x + 11.1f, -.85f, z, .28f, .08f, 5, layer, MARKING);
      if (seed % 5 == 0 && buildings.size() < 20000) {
        fixed(x - 8.1f, 0, z + 8.1f, .65f, 3, .65f, layer, TRUNK);
        fixed(x - 8.1f, 2.1f, z + 8.1f, 2.8f, 3.8f, 2.8f, layer, GREEN);
      }
      // A few parked cars give the streets an immediate sense of scale.
      if (seed % 11 == 0) {
        fixed(x + 10.6f, -.7f, z + 5.5f, 1.25f, 1.1f, 3, layer, seed % 2 == 0 ? ROOF : MARKING);
        fixed(x + 10.6f, .4f, z + 5.5f, .95f, .6f, 1.5f, layer, ASPHALT);
      }
    }
    // Join adjacent lots with broad avenues, sidewalks and a central reservation.
    Map<String, float[]> sectors = new LinkedHashMap<>();
    Set<String> occupied = new HashSet<>();
    for (var b : buildings.values()) {
      occupied.add(Math.round(b.cell.x()) + ":" + Math.round(b.cell.z()) + ":" + b.cell.layer());
      int sx = Math.round((b.cell.x() - Math.floorMod(Math.round(b.cell.x()), 220)) / 220f);
      int sz = Math.round((b.cell.z() - Math.floorMod(Math.round(b.cell.z()), 220)) / 220f);
      String key = sx + ":" + sz + ":" + b.cell.layer();
      sectors.putIfAbsent(key, new float[] {sx * 220, sz * 220, b.cell.layer()});
    }
    for (var s : sectors.values()) {
      float x = s[0], z = s[1];
      int layer = (int) s[2];
      for (int row = 0; row < 8; row++)
        for (int col = 0; col < 8; col++) {
          float px = x + col * 23, pz = z + row * 23;
          if (occupied.contains(Math.round(px) + ":" + Math.round(pz) + ":" + layer)) continue;
          fixed(px, -2, pz, 23, 2, 23, layer, ASPHALT);
          fixed(px, -.7f, pz, 19.5f, .7f, 19.5f, layer, PAVEMENT);
          fixed(px, -.05f, pz, 16, .25f, 16, layer, GREEN);
          fixed(px, .2f, pz, 2, .1f, 16, layer, PAVEMENT);
          fixed(px - 5, .2f, pz + 4, 1, 4, 1, layer, TRUNK);
          fixed(px - 5, 3.2f, pz + 4, 5, 6, 5, layer, GREEN);
          fixed(px + 4, .2f, pz - 4, 1, 3, 1, layer, TRUNK);
          fixed(px + 4, 2.4f, pz - 4, 4, 5, 4, layer, GREEN);
        }
      fixed(x + 80.5f, -2.1f, z - 29.5f, 220, 2, 36, layer, ASPHALT);
      fixed(x - 29.5f, -2.1f, z + 80.5f, 36, 2, 220, layer, ASPHALT);
      fixed(x + 80.5f, -.7f, z - 12, 184, .6f, 2.5f, layer, PAVEMENT);
      fixed(x - 12, -.7f, z + 80.5f, 2.5f, .6f, 184, layer, PAVEMENT);
      for (int i = 0; i < 9; i++) {
        fixed(x - 10 + i * 23, -.04f, z - 29.5f, 9, .06f, .6f, layer, MARKING);
        fixed(x - 29.5f, -.04f, z - 10 + i * 23, .6f, .06f, 9, layer, MARKING);
      }
      for (int i = 0; i < 5; i++) {
        fixed(x - 21 + i * 3, -.03f, z - 42, 1.5f, .06f, 7, layer, ROOF);
        fixed(x - 42, -.03f, z - 21 + i * 3, 7, .06f, 1.5f, layer, ROOF);
      }
    }
    mesh.finish();
    dirty = false;
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
    yaw -= dx * .3f;
    pitch = MathUtils.clamp(pitch + dy * .25f, 12, 85);
  }

  public void zoom(float amount) {
    distance = MathUtils.clamp(distance * (1 + amount * .09f), 65, 25000);
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
    if (previousExploded != exploded || previousSpacing != spacing) {
      reset();
      previousExploded = exploded;
      previousSpacing = spacing;
    }
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
      float y = b.cell.layer() * spacing * explode, w = b.cell.width();
      b.bounds.min.set(b.cell.x() - w / 2, y, b.cell.z() - w / 2);
      b.bounds.max.set(b.cell.x() + w / 2, y + b.height + 10, b.cell.z() + w / 2);
      b.bounds.update();
      if (Math.abs(camera.position.x - b.cell.x()) < w / 2 + 5
          && Math.abs(camera.position.z - b.cell.z()) < w / 2 + 5)
        camera.position.y = Math.max(camera.position.y, y + b.height + 15);
    }
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
    mesh.render(camera, progress, spacing * explode, selected() == null ? -1 : selected().renderId);
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
        float d = hit.dst2(camera.position);
        if (d < nearest) {
          nearest = d;
          found = b.file.path();
        }
      }
    return found;
  }

  public void close() {
    mesh.close();
  }
}
