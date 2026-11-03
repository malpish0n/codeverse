package dev.codeverse.render;

/** Exponential damping has the same response at 30, 60 and 120 Hz. */
final class Motion {
  private Motion() {}

  static float zoomDistance(float distance, float amount) {
    if (!Float.isFinite(amount)) return distance;
    return (float)
        Math.max(25, Math.min(25000, distance * Math.exp(Math.max(-8, Math.min(8, amount)) * .12)));
  }

  static float orbitYaw(float yaw, float dx) {
    return yaw + dx * .3f;
  }

  static float orbitPitch(float pitch, float dy) {
    return Math.max(8, Math.min(85, pitch - dy * .25f));
  }

  static float blend(float rate, float delta) {
    return (float) -Math.expm1(-rate * Math.max(0, delta));
  }
}
