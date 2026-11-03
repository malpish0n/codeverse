package dev.codeverse.model;

import java.util.*;

/** Seeded subdivision of connected city regions; all randomness is local to a region. */
public final class CityGenerator {
  public enum Style {
    ORGANIC,
    DOWNTOWN,
    GARDEN
  }

  public record Settings(long seed, Style style, float scale) {
    public Settings {
      if (style == null) style = Style.ORGANIC;
      if (!Float.isFinite(scale)) scale = 1.4f;
      scale = Math.max(.8f, Math.min(2.5f, scale));
    }

    public static Settings defaults() {
      return new Settings(20261103L, Style.ORGANIC, 1.4f);
    }
  }

  public record Point(float x, float z) {}

  public record Road(List<Point> points, float width, boolean avenue) {}

  public record Lot(float x, float z, float width, float depth, float angle, int block) {}

  public record Block(
      int id, List<Point> outline, List<Lot> lots, List<Point> trees, boolean park) {}

  public record Region(List<Block> blocks, List<Road> roads) {}

  public record Plan(List<Block> blocks, List<Road> roads) {
    public static Plan empty() {
      return new Plan(List.of(), List.of());
    }
  }

  private final Settings settings;
  private final List<Block> blocks = new ArrayList<>();
  private final List<Road> roads = new ArrayList<>();
  private SplittableRandom random;
  private int blockId;

  public CityGenerator(Settings settings) {
    this.settings = settings;
  }

  public Region region(int index) {
    blocks.clear();
    roads.clear();
    blockId = index * 1024;
    random = new SplittableRandom(settings.seed() ^ mix(index + 1));
    int[] tile = spiral(index);
    float x = tile[0] * 640, z = tile[1] * 520;
    // Borders share the same world-space deformation, so adjoining regions meet exactly.
    road(x, z, x + 640, z, 22, true);
    road(x + 640, z, x + 640, z + 520, 22, true);
    road(x + 640, z + 520, x, z + 520, 22, true);
    road(x, z + 520, x, z, 22, true);
    divide(x, z, 640, 520, 0);
    return new Region(List.copyOf(blocks), List.copyOf(roads));
  }

  private void divide(float x, float z, float w, float d, int level) {
    float limit =
        settings.style() == Style.DOWNTOWN ? 150 : settings.style() == Style.GARDEN ? 205 : 175;
    if (Math.max(w, d) < limit || level >= 6) {
      block(x, z, w, d);
      return;
    }
    boolean vertical = w > d * 1.25f || (w > d * .8f && random.nextBoolean());
    if ((vertical ? w : d) < 190) vertical = !vertical;
    if ((vertical ? w : d) < 190) {
      block(x, z, w, d);
      return;
    }
    float fraction = (float) random.nextDouble(.34, .66);
    float split = Math.max(90, Math.min((vertical ? w : d) - 90, (vertical ? w : d) * fraction));
    if (vertical) {
      road(x + split, z, x + split, z + d, level < 2 ? 16 : 10, level < 2);
      divide(x, z, split, d, level + 1);
      divide(x + split, z, w - split, d, level + 1);
    } else {
      road(x, z + split, x + w, z + split, level < 2 ? 16 : 10, level < 2);
      divide(x, z, w, split, level + 1);
      divide(x, z + split, w, d - split, level + 1);
    }
  }

  private void block(float x, float z, float w, float d) {
    int id = blockId++;
    boolean park =
        random.nextDouble()
            < (settings.style() == Style.GARDEN
                ? .27
                : settings.style() == Style.DOWNTOWN ? .06 : .13);
    List<Point> outline =
        List.of(point(x, z), point(x, z + d), point(x + w, z + d), point(x + w, z));
    List<Lot> lots = new ArrayList<>();
    List<Point> trees = new ArrayList<>();
    float margin = 23, insideW = w - 2 * margin, insideD = d - 2 * margin;
    float step =
        settings.style() == Style.DOWNTOWN ? 27 : settings.style() == Style.GARDEN ? 39 : 33;
    int cols = Math.max(1, (int) (insideW / step)), rows = Math.max(1, (int) (insideD / step));
    float dx = insideW / cols, dz = insideD / rows;
    for (int row = 0; row < rows; row++)
      for (int col = 0; col < cols; col++) {
        float lx = x + margin + (col + .5f) * dx, lz = z + margin + (row + .5f) * dz;
        boolean courtyard =
            rows >= 3 && cols >= 3 && row > 0 && row < rows - 1 && col > 0 && col < cols - 1;
        if (park || courtyard) {
          trees.add(point(lx, lz));
          continue;
        }
        float jitter = Math.min(dx, dz) * .045f;
        lx += (float) random.nextDouble(-jitter, jitter);
        lz += (float) random.nextDouble(-jitter, jitter);
        float side = Math.min(dx, dz);
        float width = side * (float) random.nextDouble(.50, .66),
            depth = side * (float) random.nextDouble(.50, .68);
        Point center = point(lx, lz), tangent = point(lx + 1, lz);
        float angle =
            (float) Math.toDegrees(Math.atan2(tangent.z - center.z, tangent.x - center.x));
        if (col == 0 || col == cols - 1) angle += 90;
        lots.add(
            new Lot(
                center.x, center.z, width * settings.scale(), depth * settings.scale(), angle, id));
      }
    blocks.add(new Block(id, outline, List.copyOf(lots), List.copyOf(trees), park));
  }

  private void road(float x, float z, float toX, float toZ, float width, boolean avenue) {
    int steps = Math.max(1, (int) Math.ceil(Math.hypot(toX - x, toZ - z) / 22));
    List<Point> points = new ArrayList<>();
    for (int i = 0; i <= steps; i++) {
      float t = (float) i / steps;
      points.add(point(x + (toX - x) * t, z + (toZ - z) * t));
    }
    roads.add(new Road(List.copyOf(points), width * settings.scale(), avenue));
  }

  private Point point(float x, float z) {
    double phase = (settings.seed() & 0xffff) * .00017;
    double bend =
        settings.style() == Style.DOWNTOWN ? 12 : settings.style() == Style.GARDEN ? 52 : 42;
    float px = (float) (x + bend * Math.sin(z / 260.0 + phase) + bend * .25 * Math.sin(x / 410.0));
    float pz =
        (float) (z + bend * .7 * Math.sin(x / 310.0 + phase) + bend * .2 * Math.sin(z / 490.0));
    return new Point(px * settings.scale(), pz * settings.scale());
  }

  private static long mix(long x) {
    x = (x ^ (x >>> 30)) * 0xbf58476d1ce4e5b9L;
    x = (x ^ (x >>> 27)) * 0x94d049bb133111ebL;
    return x ^ (x >>> 31);
  }

  private static int[] spiral(int n) {
    if (n == 0) return new int[] {0, 0};
    int k = (int) Math.ceil((Math.sqrt(n + 1) - 1) / 2),
        t = 2 * k,
        m = (2 * k + 1) * (2 * k + 1) - 1;
    if (n >= m - t) return new int[] {k - (m - n), -k};
    m -= t;
    if (n >= m - t) return new int[] {-k, -k + (m - n)};
    m -= t;
    if (n >= m - t) return new int[] {-k + (m - n), k};
    return new int[] {k, k - (m - t - n)};
  }
}
