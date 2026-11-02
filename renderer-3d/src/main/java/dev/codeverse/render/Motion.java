package dev.codeverse.render;

/** Exponential damping has the same response at 30, 60 and 120 Hz. */
final class Motion {
  private Motion() {}

  static float blend(float rate, float delta) {
    return (float) -Math.expm1(-rate * Math.max(0, delta));
  }
}
