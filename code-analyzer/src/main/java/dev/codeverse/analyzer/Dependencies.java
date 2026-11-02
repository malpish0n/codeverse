package dev.codeverse.analyzer;

import dev.codeverse.model.Data.*;
import java.util.*;
import java.util.regex.*;

/** Java source import heuristic; reflection and dynamic dispatch are intentionally unknown. */
public final class Dependencies {
  private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
  private static final Pattern IMPORT =
      Pattern.compile("(?m)^\\s*import\\s+(?:static\\s+)?([\\w.*]+)\\s*;");

  public static List<Edge> analyze(Snapshot snapshot) {
    Map<String, String> names = new HashMap<>();
    for (var f : snapshot.files())
      if (f.language().equals("Java")) {
        Matcher p = PACKAGE.matcher(f.source());
        String base =
            f.path().substring(f.path().lastIndexOf('/') + 1).replaceFirst("\\.java$", "");
        names.put((p.find() ? p.group(1) + "." : "") + base, f.path());
      }
    Set<Edge> edges = new LinkedHashSet<>();
    for (var f : snapshot.files())
      if (f.language().equals("Java")) {
        Matcher m = IMPORT.matcher(f.source());
        while (m.find()) {
          String imp = m.group(1);
          if (imp.endsWith(".*")) {
            String prefix = imp.substring(0, imp.length() - 1);
            for (var e : names.entrySet())
              if (e.getKey().startsWith(prefix)
                  && !e.getKey().substring(prefix.length()).contains(".")
                  && !e.getValue().equals(f.path()))
                edges.add(new Edge(f.path(), e.getValue(), "Java wildcard import heuristic", .6));
          } else {
            String candidate = imp;
            while (candidate.contains(".")) {
              String target = names.get(candidate);
              if (target != null) {
                if (!target.equals(f.path()))
                  edges.add(new Edge(f.path(), target, "Java import heuristic", .8));
                break;
              }
              candidate = candidate.substring(0, candidate.lastIndexOf('.'));
            }
          }
        }
      }
    return List.copyOf(edges);
  }

  public static Set<String> reach(List<Edge> edges, String start, int depth, boolean reverse) {
    Set<String> seen = new LinkedHashSet<>();
    seen.add(start);
    Set<String> frontier = Set.of(start);
    for (int i = 0; i < depth; i++) {
      Set<String> next = new LinkedHashSet<>();
      for (Edge e : edges) {
        String a = reverse ? e.target() : e.source(), b = reverse ? e.source() : e.target();
        if (frontier.contains(a) && seen.add(b)) next.add(b);
      }
      frontier = next;
    }
    seen.remove(start);
    return seen;
  }

  public static Set<String> cycles(List<Edge> edges) {
    Map<String, List<String>> forward = new HashMap<>(), reverse = new HashMap<>();
    Set<String> nodes = new HashSet<>();
    for (var e : edges) {
      nodes.add(e.source());
      nodes.add(e.target());
      forward.computeIfAbsent(e.source(), k -> new ArrayList<>()).add(e.target());
      reverse.computeIfAbsent(e.target(), k -> new ArrayList<>()).add(e.source());
    }
    Set<String> seen = new HashSet<>();
    List<String> order = new ArrayList<>();
    record Visit(String node, boolean finish) {}
    for (String root : nodes)
      if (!seen.contains(root)) {
        ArrayDeque<Visit> stack = new ArrayDeque<>();
        stack.push(new Visit(root, false));
        while (!stack.isEmpty()) {
          Visit v = stack.pop();
          if (v.finish()) {
            order.add(v.node());
            continue;
          }
          if (!seen.add(v.node())) continue;
          stack.push(new Visit(v.node(), true));
          for (String next : forward.getOrDefault(v.node(), List.of()))
            if (!seen.contains(next)) stack.push(new Visit(next, false));
        }
      }
    Collections.reverse(order);
    seen.clear();
    Set<String> result = new HashSet<>();
    for (String root : order)
      if (seen.add(root)) {
        Set<String> component = new HashSet<>();
        ArrayDeque<String> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
          String n = stack.pop();
          component.add(n);
          for (String next : reverse.getOrDefault(n, List.of()))
            if (seen.add(next)) stack.push(next);
        }
        if (component.size() > 1 || forward.getOrDefault(root, List.of()).contains(root))
          result.addAll(component);
      }
    return result;
  }
}
