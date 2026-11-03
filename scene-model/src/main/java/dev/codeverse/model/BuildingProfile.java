package dev.codeverse.model;

import dev.codeverse.model.Data.FileRevision;
import java.util.*;
import java.util.regex.Pattern;

/** Lexical visualization signals, not a correctness or maintainability score. */
public record BuildingProfile(
    Model model, int richness, int compactness, int tokens, int codeLines) {
  public enum Model {
    HOUSE("House"),
    BLOCK("Urban block"),
    COURTYARD("Courtyard"),
    TERRACES("Terraced complex"),
    TOWER("Compact tower");
    public final String label;

    Model(String label) {
      this.label = label;
    }
  }

  private static final Pattern TOKEN = Pattern.compile("[\\p{L}_$][\\p{L}\\p{N}_$]*|[0-9]+|[^\\s]");
  private static final Set<String> FLOW =
      Set.of("if", "else", "for", "while", "switch", "case", "catch", "match", "when", "try");

  public static BuildingProfile analyze(FileRevision file) {
    String source = strip(file.source() == null ? "" : file.source(), file.language());
    Set<String> vocabulary = new HashSet<>();
    int tokens = 0, lines = 0, branches = 0;
    for (String line : source.split("\\R")) {
      if (line.isBlank()) continue;
      lines++;
      var matcher = TOKEN.matcher(line);
      while (matcher.find()) {
        String token = matcher.group();
        tokens++;
        if (Character.isJavaIdentifierStart(token.charAt(0))) vocabulary.add(token);
        if (FLOW.contains(token)) branches++;
      }
    }
    int richness =
        Math.min(
            100,
            (int) Math.round(100 * (1 - Math.exp(-(vocabulary.size() + branches * 2) / 65.0))));
    int compactness =
        lines == 0 ? 0 : Math.min(100, (int) Math.round(100 * tokens / (lines * 24.0)));
    Model model =
        tokens < 55
            ? Model.HOUSE
            : richness < 35
                ? Model.BLOCK
                : compactness >= 65
                    ? Model.TOWER
                    : richness >= 65 ? Model.TERRACES : Model.COURTYARD;
    return new BuildingProfile(model, richness, compactness, tokens, lines);
  }

  // Preserve line boundaries; comments and literal contents cannot inflate the signals.
  private static String strip(String source, String language) {
    boolean hash = Set.of("Python", "Config", "Other").contains(language);
    boolean slash = !Set.of("Python", "Markdown").contains(language);
    StringBuilder out = new StringBuilder(source.length());
    for (int i = 0; i < source.length(); ) {
      char c = source.charAt(i);
      if ((hash && c == '#') || (slash && source.startsWith("//", i))) {
        while (i < source.length() && source.charAt(i) != '\n') {
          out.append(' ');
          i++;
        }
      } else if (slash && source.startsWith("/*", i)) {
        out.append("  ");
        i += 2;
        while (i < source.length() && !source.startsWith("*/", i)) {
          out.append(source.charAt(i++) == '\n' ? '\n' : ' ');
        }
        if (i < source.length()) {
          out.append("  ");
          i += 2;
        }
      } else if (c == '"' || c == '\'' || c == 96) {
        String delimiter =
            source.startsWith("\"\"\"", i)
                ? "\"\"\""
                : source.startsWith("'''", i) ? "'''" : String.valueOf(c);
        out.append('0');
        i += delimiter.length();
        while (i < source.length() && !source.startsWith(delimiter, i)) {
          if (source.charAt(i) == '\\' && i + 1 < source.length()) {
            out.append(' ');
            i++;
          }
          out.append(source.charAt(i++) == '\n' ? '\n' : ' ');
        }
        if (i < source.length()) i += delimiter.length();
      } else {
        out.append(c);
        i++;
      }
    }
    return out.toString();
  }
}
