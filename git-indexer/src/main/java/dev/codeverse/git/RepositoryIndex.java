package dev.codeverse.git;

import dev.codeverse.model.Data;
import dev.codeverse.model.Data.*;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.*;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.*;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.function.*;

/** All operations read committed objects only; no checkout or repository execution. */
public final class RepositoryIndex implements AutoCloseable {
    public static final int MAX_BLOB = 1_048_576, MAX_FILES = 100_000, MAX_COMMITS = 2_000;
    private final Repository repo;
    public RepositoryIndex(Path path) throws IOException {
        Path p = path.toRealPath();
        var b = new FileRepositoryBuilder();
        if(Files.isDirectory(p.resolve("objects")) && Files.exists(p.resolve("HEAD"))) b.setGitDir(p.toFile());
        else if(Files.exists(p.resolve(".git"))) b.findGitDir(p.toFile());
        else throw new IOException("Choose a repository root (or a bare repository).");
        repo=b.setMustExist(true).build();
    }
    public String identity() throws IOException { return repo.getDirectory().getCanonicalPath(); }
    public String resolve(String ref) throws IOException {
        ObjectId id = repo.resolve(ref + "^{commit}");
        if(id==null) throw new IOException("No commit at: " + ref + ". Repository may be empty.");
        return id.name();
    }
    public List<String> refs() throws IOException {
        var result=new ArrayList<String>(); result.add("HEAD");
        for(Ref r:repo.getRefDatabase().getRefsByPrefix("refs/heads/")) result.add(Repository.shortenRefName(r.getName()));
        for(Ref r:repo.getRefDatabase().getRefsByPrefix("refs/tags/")) result.add(r.getName());
        return result;
    }
    public List<CommitInfo> history(String ref, boolean firstParent, BooleanSupplier cancel) throws IOException {
        var result=new ArrayList<CommitInfo>();
        try(var walk=new RevWalk(repo)) {
            walk.setFirstParent(firstParent); walk.sort(RevSort.TOPO); walk.markStart(walk.parseCommit(ObjectId.fromString(resolve(ref))));
            for(RevCommit c:walk) {
                check(cancel);
                result.add(new CommitInfo(c.name(), Arrays.stream(c.getParents()).map(RevCommit::name).toList(),
                    c.getCommitTime(), c.getAuthorIdent().getName(), c.getShortMessage()));
                if(result.size()>=MAX_COMMITS) break;
            }
        }
        return List.copyOf(result);
    }
    public Snapshot snapshot(String ref, BooleanSupplier cancel, Consumer<String> progress) throws IOException {
        String sha=resolve(ref); var files=new ArrayList<FileRevision>();
        try(var walk=new RevWalk(repo); var tree=new TreeWalk(repo)) {
            tree.addTree(walk.parseCommit(ObjectId.fromString(sha)).getTree()); tree.setRecursive(true);
            while(tree.next()) {
                check(cancel); if(files.size()>=MAX_FILES) throw new IOException("File limit exceeded (100,000).");
                String p=tree.getPathString(); FileMode mode=tree.getFileMode(0); ObjectId id=tree.getObjectId(0);
                String kind="text", source=""; int loc=0; long size=0;
                if(mode.equals(FileMode.GITLINK)) kind="submodule";
                else if(mode.equals(FileMode.SYMLINK)) kind="symlink";
                else {
                    ObjectLoader loader=repo.open(id, Constants.OBJ_BLOB); size=loader.getSize();
                    if(size>MAX_BLOB) kind="large (not parsed)";
                    else {
                        byte[] bytes=loader.getBytes(MAX_BLOB);
                        if(RawText.isBinary(bytes)) kind="binary";
                        else {
                            source=new String(bytes,StandardCharsets.UTF_8);
                            loc=bytes.length==0?0:1;
                            for(int i=0;i<bytes.length;i++) if(bytes[i]=='\n')loc++;
                            if(bytes.length>0&&bytes[bytes.length-1]=='\n')loc--;
                            if(source.startsWith("version https://git-lfs.github.com/spec/v1"))kind="LFS pointer";
                        }
                    }
                }
                files.add(new FileRevision(p,id.name(),loc,size,Data.language(p),Data.module(p),kind,source));
                if(files.size()%200==0)progress.accept("Reading " + files.size() + " files");
            }
        }
        return new Snapshot(sha,List.copyOf(files));
    }
    public List<Change> diff(String a, String b, boolean mergeBase) throws IOException {
        try(var walk=new RevWalk(repo)) {
            RevCommit from=walk.parseCommit(ObjectId.fromString(resolve(a))), to=walk.parseCommit(ObjectId.fromString(resolve(b)));
            if(mergeBase) {
                walk.setRevFilter(org.eclipse.jgit.revwalk.filter.RevFilter.MERGE_BASE);
                walk.markStart(from); walk.markStart(to); from=walk.next();
                if(from==null)throw new IOException("No common ancestor for A and B.");
            }
            return changes(from.getTree(),to.getTree());
        }
    }
    private List<Change> changes(RevTree from, RevTree to) throws IOException {
        var out=new ArrayList<Change>();
        try(var fmt=new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            fmt.setRepository(repo); fmt.setDetectRenames(true);
            fmt.getRenameDetector().setRenameLimit(1000);
            fmt.setBinaryFileThreshold(MAX_BLOB);
            for(DiffEntry d:fmt.scan(from,to)) {
                int add=0,del=0;
                for(Edit e:fmt.toFileHeader(d).toEditList()){ add+=e.getLengthB();del+=e.getLengthA(); }
                out.add(new Change(d.getChangeType().name(),d.getOldPath(),d.getNewPath(),add,del));
            }
        }
        return List.copyOf(out);
    }
    public Map<String,Hotspot> hotspots(String ref,int days,BooleanSupplier cancel) throws IOException {
        long since=Instant.now().minusSeconds(days*86400L).getEpochSecond();
        Map<String,int[]> values=new HashMap<>(); Map<String,Set<String>> authors=new HashMap<>();
        try(var walk=new RevWalk(repo)) {
            int count=0; walk.setFirstParent(true);walk.markStart(walk.parseCommit(ObjectId.fromString(resolve(ref))));
            for(RevCommit c:walk) {
                check(cancel);if(++count>MAX_COMMITS)break; if(c.getCommitTime()<since)continue;
                RevTree parent=c.getParentCount()==0?null:walk.parseCommit(c.getParent(0)).getTree();
                for(Change d:changes(parent,c.getTree())) {
                    String p=d.type().equals("DELETE")?d.oldPath():d.newPath();
                    int[] v=values.computeIfAbsent(p,k->new int[2]);v[0]++;v[1]+=d.added()+d.deleted();
                    authors.computeIfAbsent(p,k->new HashSet<>()).add(c.getAuthorIdent().getEmailAddress());
                }
            }
        }
        double max=values.entrySet().stream().mapToDouble(e->e.getValue()[0]*.55+e.getValue()[1]*.30+authors.get(e.getKey()).size()*.15).max().orElse(1);
        Map<String,Hotspot> out=new HashMap<>();
        for(var e:values.entrySet()){int[] v=e.getValue();int a=authors.get(e.getKey()).size();out.put(e.getKey(),new Hotspot(v[0],v[1],a,(v[0]*.55+v[1]*.30+a*.15)/Math.max(1,max)));}
        return Map.copyOf(out);
    }
    public List<CommitInfo> fileHistory(String ref,String path) throws Exception {
        var result=new ArrayList<CommitInfo>();
        try(Git git=new Git(repo)) {for(RevCommit c:git.log().add(ObjectId.fromString(resolve(ref))).addPath(path).setMaxCount(MAX_COMMITS).call())
            result.add(new CommitInfo(c.name(),Arrays.stream(c.getParents()).map(RevCommit::name).toList(),c.getCommitTime(),c.getAuthorIdent().getName(),c.getShortMessage()));}
        return List.copyOf(result);
    }
    private static void check(BooleanSupplier cancel) throws InterruptedIOException { if(cancel.getAsBoolean()||Thread.currentThread().isInterrupted())throw new InterruptedIOException("Cancelled"); }
    public void close(){repo.close();}
}
