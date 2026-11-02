package dev.codeverse.model;

import java.util.*;

public final class Data {
  private Data() {}

  public record FileRevision(
      String path,
      String blob,
      int loc,
      long bytes,
      String language,
      String module,
      String kind,
      String source) {}

  public record CommitInfo(
      String sha, List<String> parents, long timestamp, String author, String message) {}

  public record Snapshot(String sha, List<FileRevision> files) {}

  public record Change(String type, String oldPath, String newPath, int added, int deleted) {}

  public record Hotspot(int commits, int linesChanged, int authors, double score) {}

  public record Edge(String source, String target, String kind, double confidence) {}

  public record Cell(String path, float x, float z, float width, int layer) {}

  public static float height(int loc) {
    return (float) Math.min(120, Math.max(8, 8 + 16 * Math.log1p(loc) / Math.log(2)));
  }

  public static float smooth(float t) {
    t = Math.max(0, Math.min(1, t));
    return t * t * (3 - 2 * t);
  }

  public static String module(String path) {
    int i = path.lastIndexOf('/');
    return i < 0 ? "root" : path.substring(0, i);
  }

  public static String language(String path) {
    String ext = path.substring(path.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    return switch (ext) {
      case "java" -> "Java";
      case "kt", "kts" -> "Kotlin";
      case "js", "jsx" -> "JavaScript";
      case "ts", "tsx" -> "TypeScript";
      case "py" -> "Python";
      case "rs" -> "Rust";
      case "go" -> "Go";
      case "c", "h", "cpp", "hpp" -> "C/C++";
      case "md" -> "Markdown";
      case "json", "yml", "yaml", "xml", "toml", "gradle" -> "Config";
      default -> "Other";
    };
  }
}
