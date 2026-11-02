package dev.codeverse.git;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.eclipse.jgit.api.*;
import org.eclipse.jgit.lib.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RepositoryIndexTest {
    @TempDir Path temp;
    private Git git;
    @BeforeEach void setup() throws Exception {git=Git.init().setDirectory(temp.toFile()).setInitialBranch("main").call();}
    @AfterEach void close(){git.close();}
    private void write(String path,String text)throws Exception{Path p=temp.resolve(path);Files.createDirectories(p.getParent());Files.writeString(p,text);}
    private String commit(String msg)throws Exception{git.add().addFilepattern(".").call();git.add().addFilepattern(".").setUpdate(true).call();return git.commit().setAllowEmpty(true).setMessage(msg).setAuthor("Tester","test@example.invalid").call().name();}
    @Test void emptyRepositoryHasUsefulError()throws Exception{try(var r=new RepositoryIndex(temp)){assertTrue(assertThrows(IOException.class,()->r.resolve("HEAD")).getMessage().contains("empty"));}}
    @Test void diffDetectsAddModifyDeleteRenameAndUnicodeWithoutCheckout()throws Exception{
        write("src/Old.java","package app;\npublic class Old {\n"+"// stable line\n".repeat(30)+"}\n");write("delete.txt","gone\n");write("modify.txt","before\n");String a=commit("first");
        Files.move(temp.resolve("src/Old.java"),temp.resolve("src/New.java"));Files.delete(temp.resolve("delete.txt"));write("modify.txt","after\n");write("źródła/Nowy.java","one\ntwo\n");String b=commit("second");String head=git.getRepository().resolve("HEAD").name();
        try(var r=new RepositoryIndex(temp)){
            var d=r.diff(a,b,false);assertEquals(Set.of("ADD","MODIFY","DELETE","RENAME"),new HashSet<>(d.stream().map(x->x.type()).toList()));
            var s=r.snapshot(a,()->false,x->{});assertEquals(3,s.files().size());assertTrue(s.files().stream().anyMatch(f->f.path().equals("src/Old.java")));
            assertEquals(2,r.snapshot(b,()->false,x->{}).files().stream().filter(f->f.path().contains("Nowy")).findFirst().orElseThrow().loc());
            assertEquals(head,git.getRepository().resolve("HEAD").name());assertFalse(Files.exists(temp.resolve("src/Old.java")));assertTrue(git.status().call().isClean());
            var nativeDiff=new ProcessBuilder("git","-C",temp.toString(),"diff","--name-status","-M",a,b).start();String nativeOutput=new String(nativeDiff.getInputStream().readAllBytes());assertEquals(0,nativeDiff.waitFor());assertEquals(d.size(),nativeOutput.lines().count());assertTrue(nativeOutput.contains("R100"));
        }
    }
    @Test void mergeBaseAndFirstParentHaveCorrectSemantics()throws Exception{
        write("base.txt","base");String base=commit("base");git.branchCreate().setName("feature").call();write("main.txt","main");String main=commit("main");git.checkout().setName("feature").call();write("feature.txt","feature");String feature=commit("feature");
        try(var r=new RepositoryIndex(temp)){assertEquals(2,r.diff(main,feature,false).size());var diff=r.diff(main,feature,true);assertEquals(1,diff.size());assertEquals("feature.txt",diff.getFirst().newPath());}
        git.checkout().setName("main").call();git.merge().include(ObjectId.fromString(feature)).setMessage("merge").call();
        try(var r=new RepositoryIndex(temp)){var first=r.history("HEAD",true,()->false);var all=r.history("HEAD",false,()->false);assertEquals(3,first.size());assertEquals(4,all.size());assertEquals(2,first.getFirst().parents().size());}
    }
    @Test void detachedHeadTagsEmptyCommitsAndCancellation()throws Exception{
        write("file","x");String sha=commit("one");commit("empty");git.tag().setName("v1").setAnnotated(false).call();git.checkout().setName(sha).call();
        try(var r=new RepositoryIndex(temp)){assertEquals(sha,r.resolve("HEAD"));assertTrue(r.refs().contains("refs/tags/v1"));assertThrows(InterruptedIOException.class,()->r.snapshot("HEAD",()->true,x->{}));assertThrows(InterruptedIOException.class,()->r.history("HEAD",true,()->true));}
    }
    @Test void skipsBinaryLargeLfsAndSymlinkTargets()throws Exception{
        Files.write(temp.resolve("binary.bin"),new byte[]{0,1,2});write("large.txt","x".repeat(RepositoryIndex.MAX_BLOB+1));write("lfs.bin","version https://git-lfs.github.com/spec/v1\noid sha256:000\nsize 12345\n");
        try{Files.createSymbolicLink(temp.resolve("outside"),Path.of("/etc/passwd"));}catch(Exception ignored){}
        commit("special files");try(var r=new RepositoryIndex(temp)){var s=r.snapshot("HEAD",()->false,x->{});var map=new HashMap<String,String>();s.files().forEach(f->map.put(f.path(),f.kind()));assertEquals("binary",map.get("binary.bin"));assertEquals("large (not parsed)",map.get("large.txt"));assertEquals("LFS pointer",map.get("lfs.bin"));if(map.containsKey("outside"))assertEquals("symlink",map.get("outside"));}
    }
    @Test void bareRepositoryAndHotspots()throws Exception{
        write("a.java","a\n");commit("one");write("a.java","a\nb\n");commit("two");Path bare=temp.resolveSibling(temp.getFileName()+"-bare");try(Git clone=Git.cloneRepository().setURI(temp.toUri().toString()).setDirectory(bare.toFile()).setBare(true).call();var r=new RepositoryIndex(bare)){
            assertEquals(1,r.snapshot("HEAD",()->false,x->{}).files().size());var h=r.hotspots("HEAD",30,()->false).get("a.java");assertEquals(2,h.commits());assertEquals(1,h.authors());assertEquals(1,h.score());
        }
    }
    @Test void demoContainsRealBranchHistory()throws Exception{Path demo=temp.resolve("demo");DemoRepository.create(demo);try(var r=new RepositoryIndex(demo)){assertEquals(288,r.snapshot("main",()->false,x->{}).files().size());assertEquals(12,r.history("main",true,()->false).size());assertEquals(4,r.diff("main","feature/observability",false).size());}}
}
