package dev.codeverse.desktop;

import com.badlogic.gdx.backends.lwjgl3.*;

public final class Launcher {
  public static void main(String[] args) {
    Thread.setDefaultUncaughtExceptionHandler(
        (thread, error) -> {
          try {
            java.nio.file.Path dir =
                java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "codeverse-crashes");
            java.nio.file.Files.createDirectories(dir);
            try (var log =
                new java.io.PrintWriter(
                    java.nio.file.Files.newBufferedWriter(
                        dir.resolve("crash-" + System.currentTimeMillis() + ".log")))) {
              error.printStackTrace(log);
            }
          } catch (Exception ignored) {
          }
          error.printStackTrace();
        });
    Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
    config.setTitle("CODEVERSE / The living history of code");
    config.setWindowedMode(1440, 900);
    config.setWindowSizeLimits(1100, 720, -1, -1);
    config.useVsync(true);
    config.setForegroundFPS(60);
    config.disableAudio(true);
    if (args.length > 0 && args[0].equals("--benchmark")) {
      config.useVsync(false);
      config.setForegroundFPS(0);
      new Lwjgl3Application(
          new RenderBenchmark(Integer.parseInt(args[1]), java.nio.file.Path.of(args[2])), config);
      return;
    }
    boolean showcase = args.length > 0 && args[0].equals("--showcase");
    CodeverseApp app = new CodeverseApp(args.length > 0 && !showcase ? args[0] : null);
    if (showcase) app.recordDemo(java.nio.file.Path.of(args[1]));
    config.setWindowListener(
        new Lwjgl3WindowAdapter() {
          @Override
          public void filesDropped(String[] files) {
            app.dropped(files);
          }
        });
    new Lwjgl3Application(app, config);
  }
}
