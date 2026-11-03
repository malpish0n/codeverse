package dev.codeverse.render;

import com.badlogic.gdx.graphics.*;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.utils.FloatArray;
import com.badlogic.gdx.utils.ShortArray;
import java.util.*;

/** Static urban geometry; height and district motion are interpolated on the GPU. */
final class CityMesh implements AutoCloseable {
  private final List<Mesh> chunks = new ArrayList<>();
  private final FloatArray vertices = new FloatArray();
  private final ShortArray indices = new ShortArray();
  private static final int STRIDE = 12;
  private int buildingId = -1;
  private float originX, originZ, cos = 1, sin = 0;

  void transform(float x, float z, float degrees) {
    originX = x;
    originZ = z;
    cos = (float) Math.cos(Math.toRadians(degrees));
    sin = (float) Math.sin(Math.toRadians(degrees));
  }

  void surface(
      java.util.List<dev.codeverse.model.CityGenerator.Point> corners,
      float y,
      int layer,
      Color color) {
    if (vertices.size / STRIDE + 4 > 60000) flush();
    float[] p = new float[12];
    for (int i = 0; i < 4; i++) {
      p[i * 3] = corners.get(i).x();
      p[i * 3 + 1] = y;
      p[i * 3 + 2] = corners.get(i).z();
    }
    face(p, 0, 1, 0, color.toFloatBits(), -1, 0, y, y, layer);
  }

  void building(int id) {
    buildingId = id;
  }

  private final ShaderProgram shader =
      new ShaderProgram(
          """
          attribute vec3 a_position;
          attribute vec3 a_normal;
          attribute vec4 a_color;
          attribute vec2 a_texCoord0;
          attribute vec3 a_motion;
          uniform mat4 u_projection;
          uniform float u_progress;
          uniform float u_lift;
          uniform float u_selected;
          varying vec4 v_color;
          varying vec2 v_uv;
          void main() {
            vec3 p = a_position;
            p.y = mix(a_motion.x, p.y, u_progress) + a_motion.y * u_lift;
            float light = 0.52 + 0.48 * max(dot(a_normal, normalize(vec3(-0.4, 0.85, 0.3))), 0.0);
            vec3 color = a_color.rgb;
            if (a_motion.z >= 0.0 && abs(a_motion.z - u_selected) < 0.1) color = vec3(0.35, 0.96, 0.95);
            v_color = vec4(color * light, 1.0);
            v_uv = a_texCoord0;
            gl_Position = u_projection * vec4(p, 1.0);
          }
          """,
          """
          #ifdef GL_ES
          precision mediump float;
          #endif
          varying vec4 v_color;
          varying vec2 v_uv;
          void main() {
            vec3 color = v_color.rgb;
            if (v_uv.x >= 0.0) {
              vec2 tile = fract(v_uv);
              vec2 aa = max(fwidth(v_uv), vec2(0.015));
              float window = smoothstep(0.22-aa.x, 0.22+aa.x, tile.x)
                           * (1.0-smoothstep(0.78-aa.x, 0.78+aa.x, tile.x))
                           * smoothstep(0.24-aa.y, 0.24+aa.y, tile.y)
                           * (1.0-smoothstep(0.72-aa.y, 0.72+aa.y, tile.y));
              window *= 1.0-smoothstep(0.35, 1.0, max(aa.x, aa.y));
              float lit = step(0.72, fract(sin(dot(floor(v_uv), vec2(12.9898, 78.233))) * 43758.5453));
              vec3 glass = mix(vec3(0.10, 0.19, 0.24), vec3(0.83, 0.71, 0.48), lit);
              color = mix(color, glass, window * 0.85);
            }
            gl_FragColor = vec4(color, 1.0);
          }
          """);

  CityMesh() {
    if (!shader.isCompiled()) throw new IllegalStateException(shader.getLog());
  }

  void clear() {
    for (var mesh : chunks) mesh.dispose();
    chunks.clear();
    vertices.clear();
    indices.clear();
  }

  void box(
      float x,
      float base,
      float z,
      float w,
      float h,
      float d,
      float oldBase,
      float oldHeight,
      int layer,
      Color color,
      boolean windows) {
    if (vertices.size / STRIDE + 24 > 60000) flush();
    float x0 = x - w / 2,
        x1 = x + w / 2,
        z0 = z - d / 2,
        z1 = z + d / 2,
        top = base + h,
        oldTop = oldBase + oldHeight;
    float packed = color.toFloatBits();
    face(
        new float[] {x0, base, z1, x1, base, z1, x1, top, z1, x0, top, z1},
        0,
        0,
        1,
        packed,
        windows ? w / 4 : -1,
        h / 5,
        oldBase,
        oldTop,
        layer);
    face(
        new float[] {x1, base, z0, x0, base, z0, x0, top, z0, x1, top, z0},
        0,
        0,
        -1,
        packed,
        windows ? w / 4 : -1,
        h / 5,
        oldBase,
        oldTop,
        layer);
    face(
        new float[] {x1, base, z1, x1, base, z0, x1, top, z0, x1, top, z1},
        1,
        0,
        0,
        packed,
        windows ? d / 4 : -1,
        h / 5,
        oldBase,
        oldTop,
        layer);
    face(
        new float[] {x0, base, z0, x0, base, z1, x0, top, z1, x0, top, z0},
        -1,
        0,
        0,
        packed,
        windows ? d / 4 : -1,
        h / 5,
        oldBase,
        oldTop,
        layer);
    face(
        new float[] {x0, top, z1, x1, top, z1, x1, top, z0, x0, top, z0},
        0,
        1,
        0,
        packed,
        -1,
        0,
        oldTop,
        oldTop,
        layer);
    face(
        new float[] {x0, base, z0, x1, base, z0, x1, base, z1, x0, base, z1},
        0,
        -1,
        0,
        packed,
        -1,
        0,
        oldBase,
        oldBase,
        layer);
  }

  private void face(
      float[] p,
      float nx,
      float ny,
      float nz,
      float color,
      float u,
      float v,
      float low,
      float high,
      int layer) {
    int start = vertices.size / STRIDE;
    for (int i = 0; i < 4; i++) {
      vertices.add(
          originX + p[i * 3] * cos - p[i * 3 + 2] * sin,
          p[i * 3 + 1],
          originZ + p[i * 3] * sin + p[i * 3 + 2] * cos);
      vertices.add(nx * cos - nz * sin, ny, nx * sin + nz * cos);
      vertices.add(color);
      vertices.add(u < 0 ? -1 : ((i == 1 || i == 2) ? u : 0), i >= 2 ? v : 0);
      vertices.add(i >= 2 ? high : low, layer, buildingId);
    }
    for (int index : new int[] {0, 1, 2, 2, 3, 0}) indices.add((short) (start + index));
  }

  void finish() {
    flush();
  }

  private void flush() {
    if (vertices.size == 0) return;
    Mesh mesh =
        new Mesh(
            true,
            vertices.size / STRIDE,
            indices.size,
            VertexAttribute.Position(),
            VertexAttribute.Normal(),
            VertexAttribute.ColorPacked(),
            VertexAttribute.TexCoords(0),
            new VertexAttribute(VertexAttributes.Usage.Generic, 3, "a_motion"));
    mesh.setVertices(vertices.items, 0, vertices.size);
    mesh.setIndices(indices.items, 0, indices.size);
    chunks.add(mesh);
    vertices.clear();
    indices.clear();
  }

  void render(Camera camera, float progress, float lift, int selected) {
    shader.bind();
    shader.setUniformMatrix("u_projection", camera.combined);
    shader.setUniformf("u_progress", progress);
    shader.setUniformf("u_lift", lift);
    shader.setUniformf("u_selected", (float) selected);
    for (var mesh : chunks) mesh.render(shader, GL20.GL_TRIANGLES);
  }

  public void close() {
    clear();
    shader.dispose();
  }
}
