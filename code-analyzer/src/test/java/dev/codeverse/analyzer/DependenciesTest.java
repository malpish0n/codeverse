package dev.codeverse.analyzer;
import dev.codeverse.model.Data.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class DependenciesTest {
    private FileRevision file(String n,String source){return new FileRevision(n+".java","",1,1,"Java","app","text",source);}
    @Test void resolvesImportsAndCycles(){var s=new Snapshot("x",List.of(file("A","package app;\nimport app.B;\nclass A {}"),file("B","package app;\nimport app.C;\nclass B {}"),file("C","package app;\nimport app.A;\nclass C {}")));var edges=Dependencies.analyze(s);assertEquals(3,edges.size());assertEquals(Set.of("B.java"),Dependencies.reach(edges,"A.java",1,false));assertEquals(Set.of("B.java","C.java"),Dependencies.reach(edges,"A.java",2,false));assertEquals(Set.of("C.java"),Dependencies.reach(edges,"A.java",1,true));assertEquals(Set.of("A.java","B.java","C.java"),Dependencies.cycles(edges));}
    @Test void externalImportsDoNotInventDependencies(){var s=new Snapshot("x",List.of(file("A","package app;\nimport external.Unknown;\nclass A {}")));assertTrue(Dependencies.analyze(s).isEmpty());}
}
