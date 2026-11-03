package dev.codeverse.model;

import dev.codeverse.model.CityGenerator.*;
import dev.codeverse.model.Data.Cell;
import java.util.*;

/** Append-only parcel allocation keeps existing files still as a repository grows. */
public final class StableLayout {
  public static final int VERSION = 3;
  private final Map<String, Cell> cells = new LinkedHashMap<>();
  private final Map<String, Integer> modules = new LinkedHashMap<>();
  private final Map<String, ArrayDeque<Lot>> available = new HashMap<>();
  private final Map<District, ArrayDeque<Block>> freeBlocks = new EnumMap<>(District.class);
  private final Map<District, Integer> regions = new EnumMap<>(District.class);
  private final Map<String, District> districts = new LinkedHashMap<>();
  private final List<Block> blocks = new ArrayList<>();
  private final LinkedHashMap<String, Road> roads = new LinkedHashMap<>();
  private Settings settings = Settings.defaults();
  private Plan plan = Plan.empty();
  private boolean planDirty;

  public void configure(Settings settings) {
    clear();
    this.settings = Objects.requireNonNull(settings);
  }

  public Settings settings() {
    return settings;
  }

  public Map<String, Cell> reserve(Collection<String> paths) {
    paths.stream()
        .sorted()
        .forEach(
            p -> reserveOne(p, District.values()[Math.floorMod(Data.module(p).hashCode(), 4)]));
    return Map.copyOf(cells);
  }

  public Map<String, Cell> reserveFiles(Collection<Data.FileRevision> files) {
    files.stream()
        .sorted(Comparator.comparing(Data.FileRevision::path))
        .forEach(f -> reserveOne(f.path(), District.forProfile(BuildingProfile.analyze(f))));
    return Map.copyOf(cells);
  }

  private void reserveOne(String path, District district) {
    if (cells.containsKey(path)) return;
    districts.put(path, district);
    String module = Data.module(path);
    int layer = modules.computeIfAbsent(module, k -> modules.size());
    var queue = available.computeIfAbsent(module + "/" + district.name(), k -> new ArrayDeque<>());
    if (queue.isEmpty()) {
      var blocks = freeBlocks.computeIfAbsent(district, k -> new ArrayDeque<>());
      while (blocks.isEmpty()) addRegion(district);
      queue.addAll(blocks.removeFirst().lots());
    }
    Lot lot = queue.removeFirst();
    cells.put(
        path,
        new Cell(
            path, lot.x(), lot.z(), lot.width(), layer, lot.depth(), lot.angle(), lot.block()));
  }

  private void addRegion(District district) {
    int index = regions.getOrDefault(district, 0);
    regions.put(district, index + 1);
    Region region = new CityGenerator(settings).districtRegion(index, district);
    blocks.addAll(region.blocks());
    for (Block block : region.blocks())
      if (!block.lots().isEmpty()) freeBlocks.get(district).add(block);
    for (Road road : region.roads()) {
      var a = road.points().getFirst();
      var b = road.points().getLast();
      String first = Math.round(a.x() * 100) + ":" + Math.round(a.z() * 100),
          last = Math.round(b.x() * 100) + ":" + Math.round(b.z() * 100);
      roads.putIfAbsent(first.compareTo(last) < 0 ? first + "/" + last : last + "/" + first, road);
    }
    planDirty = true;
  }

  public Plan plan() {
    if (planDirty) {
      plan = new Plan(List.copyOf(blocks), List.copyOf(roads.values()));
      planDirty = false;
    }
    return plan;
  }

  public record State(
      Map<String, Cell> cells,
      Map<String, Integer> modules,
      Map<String, Integer> counts,
      Map<String, Integer> sectors,
      int version,
      Settings settings,
      List<String> allocationOrder,
      Map<String, District> districts) {
    public State(
        Map<String, Cell> cells,
        Map<String, Integer> modules,
        Map<String, Integer> counts,
        Map<String, Integer> sectors) {
      this(cells, modules, counts, sectors, 0, null, null, null);
    }
  }

  public State state() {
    return new State(
        Map.copyOf(cells),
        Map.copyOf(modules),
        Map.of(),
        Map.of(),
        VERSION,
        settings,
        List.copyOf(cells.keySet()),
        Map.copyOf(districts));
  }

  public void restore(State state) {
    configure(state.settings() == null ? Settings.defaults() : state.settings());
    if (state.version() == VERSION && state.allocationOrder() != null && state.districts() != null)
      state
          .allocationOrder()
          .forEach(p -> reserveOne(p, state.districts().getOrDefault(p, District.EVERYDAY)));
    else reserve(state.cells().keySet());
  }

  public Map<String, Cell> cells() {
    return Map.copyOf(cells);
  }

  public void clear() {
    cells.clear();
    modules.clear();
    available.clear();
    freeBlocks.clear();
    blocks.clear();
    roads.clear();
    regions.clear();
    districts.clear();
    plan = Plan.empty();
    planDirty = false;
  }
}
