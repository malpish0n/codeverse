package dev.codeverse.render;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MotionTest {
  @Test
  void trackpadFractionalZoomAccumulatesAndReverses() {
    float distance = 900;
    for (int i = 0; i < 100; i++) distance = Motion.zoomDistance(distance, -.01f);
    assertEquals(Motion.zoomDistance(900, -1), distance, .01f);
    for (int i = 0; i < 100; i++) distance = Motion.zoomDistance(distance, .01f);
    assertEquals(900, distance, .02f);
  }

  @Test
  void zoomBoundsSurviveLargeGesturesAndInvalidInput() {
    assertEquals(25, Motion.zoomDistance(25, -100));
    assertEquals(25000, Motion.zoomDistance(25000, 100));
    assertEquals(900, Motion.zoomDistance(900, Float.NaN));
    assertTrue(Motion.zoomDistance(900, -100) > 0);
  }

  @Test
  void dragAxesFollowRequestedDirectionAndStayAboveGround() {
    assertTrue(Motion.orbitYaw(42, 20) > 42);
    assertTrue(Motion.orbitPitch(44, 20) < 44);
    assertEquals(8, Motion.orbitPitch(44, 1000));
    assertEquals(85, Motion.orbitPitch(44, -1000));
  }

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
