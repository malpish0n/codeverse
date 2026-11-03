package dev.codeverse.model;

/** Architectural zones derived from code shape, not a judgment of code quality. */
public enum District {
  HOMES("Residential homes", "819C69", .30f),
  EVERYDAY("Everyday quarter", "A58C76", .80f),
  GARDENS("Garden villas", "B9B49C", .72f),
  SKYLINE("Skyline center", "769EAF", 1.35f);
  public final String label, color;
  public final float heightScale;

  District(String label, String color, float heightScale) {
    this.label = label;
    this.color = color;
    this.heightScale = heightScale;
  }

  public static District forProfile(BuildingProfile profile) {
    return switch (profile.model()) {
      case HOUSE -> HOMES;
      case BLOCK, COURTYARD -> EVERYDAY;
      case TERRACES -> GARDENS;
      case TOWER -> SKYLINE;
    };
  }
}
