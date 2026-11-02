package dev.codeverse.git;

import dev.codeverse.model.*;
import java.nio.file.*;
import java.util.*;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.dircache.*;
import org.eclipse.jgit.lib.*;

public final class IndexBenchmark {
  public static void main(String[] args) throws Exception {
    int files = Integer.parseInt(args[0]), commits = Integer.parseInt(args[1]);
    Path output = Path.of(args[2]);
    Files.createDirectories(output);
    Path fixture = Files.createTempDirectory(output, "fixture-");
    try (Git git = Git.init().setBare(true).setDirectory(fixture.toFile()).call();
        ObjectInserter inserter = git.getRepository().newObjectInserter()) {
      byte[] body =
          ("package benchmark;\npublic class Example {\n"
                  + "  // deterministic source line\n".repeat(30)
                  + "}\n")
              .getBytes(java.nio.charset.StandardCharsets.UTF_8);
      ObjectId blob = inserter.insert(Constants.OBJ_BLOB, body);
      DirCache cache = DirCache.newInCore();
      DirCacheBuilder builder = cache.builder();
      for (int i = 0; i < files; i++) {
        DirCacheEntry e =
            new DirCacheEntry(String.format(Locale.ROOT, "src/m%05d/F%07d.java", i / 64, i));
        e.setFileMode(FileMode.REGULAR_FILE);
        e.setObjectId(blob);
        builder.add(e);
      }
      builder.finish();
      ObjectId tree = cache.writeTree(inserter), parent = null;
      for (int i = 0; i < commits; i++) {
        CommitBuilder c = new CommitBuilder();
        c.setTreeId(tree);
        if (parent != null) c.setParentId(parent);
        c.setAuthor(new PersonIdent("Benchmark", "benchmark@codeverse.local"));
        c.setCommitter(c.getAuthor());
        c.setMessage("History fixture " + i);
        parent = inserter.insert(c);
      }
      inserter.flush();
      RefUpdate head = git.getRepository().updateRef("HEAD");
      head.setNewObjectId(parent);
      head.update();
    }
    try (var index = new RepositoryIndex(fixture)) {
      long start = System.nanoTime();
      var snap = index.snapshot("HEAD", () -> false, x -> {});
      double snapshotMs = (System.nanoTime() - start) / 1e6;
      start = System.nanoTime();
      var history = index.history("HEAD", true, () -> false);
      double historyMs = (System.nanoTime() - start) / 1e6;
      start = System.nanoTime();
      var visual = Aggregation.forRendering(snap);
      new StableLayout().reserve(visual.files().stream().map(f -> f.path()).toList());
      double layoutMs = (System.nanoTime() - start) / 1e6;
      String json =
          "{\"files\":"
              + files
              + ",\"fixtureCommits\":"
              + commits
              + ",\"indexedCommits\":"
              + history.size()
              + ",\"snapshotMs\":"
              + snapshotMs
              + ",\"historyMs\":"
              + historyMs
              + ",\"layoutMs\":"
              + layoutMs
              + ",\"renderNodes\":"
              + visual.files().size()
              + ",\"note\":\"History fixture uses unchanged trees; not a worst-case churn"
              + " benchmark\"}";
      Files.writeString(output.resolve("index-" + files + ".json"), json);
      System.out.println(json);
    }
  }
}
