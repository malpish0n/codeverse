package dev.codeverse.desktop;

import com.badlogic.gdx.*;
import com.badlogic.gdx.graphics.*;
import com.badlogic.gdx.utils.ScreenUtils;
import dev.codeverse.model.*;
import dev.codeverse.model.Data.*;
import dev.codeverse.render.CityRenderer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Reproducible renderer workload; metrics distinguish observed frames from target budgets. */
public final class RenderBenchmark extends ApplicationAdapter {
  private final int count;
  private final Path output;
  private CityRenderer city;
  private int frame;
  private final List<Double> times = new ArrayList<>();
  private final List<Double> animatedTimes = new ArrayList<>();
  private long last;

  public RenderBenchmark(int count, Path output) {
    this.count = count;
    this.output = output;
  }

  public void create() {
    city = new CityRenderer();
    var files = new ArrayList<FileRevision>();
    for (int i = 0; i < count; i++)
      files.add(
          new FileRevision(
              "module" + String.format("%04d", i / 64) + "/File" + i + ".java",
              "",
              (i * 37) % 1800,
              100,
              "Java",
              "module" + (i / 64),
              "text",
              ""));
    var snap = Aggregation.forRendering(new Snapshot("benchmark", files));
    var layout = new StableLayout();
    city.setScene(
        snap,
        layout.reserve(snap.files().stream().map(FileRevision::path).toList()),
        List.of(),
        Map.of(),
        true);
    city.setPlan(layout.plan());
    last = System.nanoTime();
  }

  public void render() {
    long now = System.nanoTime();
    double ms = (now - last) / 1e6;
    last = now;
    if (frame > 180 && frame < 360) times.add(ms);
    if (frame > 360) animatedTimes.add(ms);
    if (frame >= 360) {
      city.exploded = (frame / 90) % 2 == 0;
      city.tour = true;
    }
    ScreenUtils.clear(Color.valueOf("090E19"), true);
    city.render(1f / 60f, 0, 0, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
    frame++;
    if (frame == 720) {
      try {
        Files.createDirectories(output);
        Collections.sort(times);
        Collections.sort(animatedTimes);
        double p50 = times.get(times.size() / 2), p95 = times.get((int) (times.size() * .95));
        Files.writeString(
            output.resolve("render-" + count + ".json"),
            "{\"inputFiles\":"
                + count
                + ",\"renderNodes\":"
                + city.buildings().size()
                + ",\"warmupFrames\":180,\"measuredFrames\":"
                + times.size()
                + ",\"frameMsP50\":"
                + p50
                + ",\"frameMsP95\":"
                + p95
                + ",\"animatedFrameMsP95\":"
                + animatedTimes.get((int) (animatedTimes.size() * .95))
                + ",\"animatedMeasuredFrames\":"
                + animatedTimes.size()
                + ",\"heapUsedBytes\":"
                + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
                + ",\"gpu\":\""
                + Gdx.gl.glGetString(GL20.GL_RENDERER)
                + "\"}");
        Pixmap pix =
            Pixmap.createFromFrameBuffer(
                0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
        PixmapIO.writePNG(
            Gdx.files.absolute(output.resolve("render-" + count + ".png").toString()),
            pix,
            -1,
            true);
        pix.dispose();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
      Gdx.app.exit();
    }
  }

  public void dispose() {
    city.close();
  }
}
