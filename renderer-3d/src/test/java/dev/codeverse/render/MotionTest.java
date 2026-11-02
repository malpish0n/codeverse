package dev.codeverse.render;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MotionTest {
  @Test
  void dampingIsIndependentOfFrameRate() {
    for (int hz : new int[] {30, 60, 120, 144}) {
      float value = 0;
      for (int i = 0; i < hz; i++) value += (1 - value) * Motion.blend(6, 1f / hz);
      assertEquals(1 - Math.exp(-6), value, 0.000002);
    }
  }

  @Test
  void interruptedFrameCannotOvershoot() {
    assertEquals(0, Motion.blend(6, 0));
    assertEquals(0, Motion.blend(6, -1));
    assertTrue(Motion.blend(6, 1) <= 1);
  }
}
