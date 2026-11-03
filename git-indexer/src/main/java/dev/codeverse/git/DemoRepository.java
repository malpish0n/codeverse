package dev.codeverse.git;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;

/** Creates only the app-owned demonstration repository, never an imported repository. */
public final class DemoRepository {
  public static Path create(Path path) throws Exception {
    if (Files.exists(path.resolve(".git"))) return path;
    Files.createDirectories(path);
    try (Git git = Git.init().setDirectory(path.toFile()).setInitialBranch("main").call()) {
      String[] modules = {"ui", "application", "domain", "infra", "analytics", "tests"};
      for (int step = 0; step < 24; step++) {
        for (int m = 0; m < modules.length; m++) {
          for (int n = 0; n < new int[] {9, 5, 14, 3, 8, 4}[m]; n++) {
            int index = step * new int[] {9, 5, 14, 3, 8, 4}[m] + n;
            Path file = path.resolve("src/" + modules[m] + "/" + "Component" + index + ".java");
            Files.createDirectories(file.getParent());
            StringBuilder s = new StringBuilder("package demo." + modules[m] + ";\n");
            if (m > 0) s.append("import demo." + modules[m - 1] + ".Component" + index + ";\n");
            s.append("public class Component" + index + " {\n");
            int variant = (index + m) % 5;
            if (variant == 0) s.append("  public int value() { return 1; }\n");
            else if (variant == 1) {
              s.append("  public int total(int value) {\n");
              for (int k = 0; k < 18; k++) s.append("    value += 1;\n");
              s.append("    return value;\n  }\n");
            } else {
              int fields = variant == 2 ? 32 : variant == 3 ? 90 : 55;
              for (int k = 0; k < fields; k++)
                s.append(
                    "  private int metric" + k + " = " + k + ";" + (variant == 4 ? " " : "\n"));
              s.append("\n");
            }
            s.append("}\n");
            Files.writeString(file, s);
          }
          if (step > 0)
            Files.writeString(
                path.resolve("src/" + modules[m] + "/Component0.java"),
                "// Changed during release " + step + "\n",
                StandardOpenOption.APPEND);
        }
        git.add().addFilepattern(".").call();
        var who =
            new PersonIdent(
                step % 2 == 0 ? "Alex Developer" : "Sam Maintainer",
                "demo@codeverse.local",
                Date.from(Instant.now().minusSeconds((24 - step) * 86400L)),
                TimeZone.getTimeZone("UTC"));
        git.commit()
            .setMessage(
                new String[] {
                      "Lay the foundations",
                      "Introduce domain services",
                      "Build the application layer",
                      "Add data adapters",
                      "Ship the explorer",
                      "Refine navigation",
                      "Introduce analytics",
                      "Scale the scene",
                      "Improve history playback",
                      "Optimize the renderer",
                      "Add dependency insights",
                      "Prepare the release"
                    }
                    [step % 12])
            .setAuthor(who)
            .setCommitter(who)
            .call();
      }
      git.branchCreate().setName("feature/observability").call();
      git.checkout().setName("feature/observability").call();
      Path old = path.resolve("src/ui/Component1.java"),
          renamed = path.resolve("src/ui/ExplorerPanel.java");
      Files.move(old, renamed);
      Files.delete(path.resolve("src/infra/Component2.java"));
      Files.writeString(
          path.resolve("src/analytics/TraceCollector.java"),
          "package demo.analytics;\n"
              + "import demo.domain.Component0;\n"
              + "public class TraceCollector {\n"
              + " // New tracing pipeline\n"
              + "}\n");
      Files.writeString(
          path.resolve("src/application/Component0.java"),
          "// Observability integration\n",
          StandardOpenOption.APPEND);
      git.add().addFilepattern(".").call();
      git.add().setUpdate(true).addFilepattern(".").call();
      git.commit()
          .setMessage("Add tracing, rename explorer, remove legacy adapter")
          .setAuthor("Sam Maintainer", "demo@codeverse.local")
          .call();
      git.checkout().setName("main").call();
    }
    return path;
  }
}
