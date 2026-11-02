package dev.codeverse.analyzer;

import dev.codeverse.model.Data.*;
import java.util.*;
import java.util.regex.*;

/** Java source import heuristic; reflection and dynamic dispatch are intentionally unknown. */
public final class Dependencies {
    private static final Pattern PACKAGE=Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern IMPORT=Pattern.compile("(?m)^\\s*import\\s+(?:static\\s+)?([\\w.*]+)\\s*;");
    public static List<Edge> analyze(Snapshot snapshot){
        Map<String,String> names=new HashMap<>();
        for(var f:snapshot.files())if(f.language().equals("Java")){
            Matcher p=PACKAGE.matcher(f.source()); String base=f.path().substring(f.path().lastIndexOf('/')+1).replaceFirst("\\.java$","");
            names.put((p.find()?p.group(1)+".":"")+base,f.path());
        }
        Set<Edge> edges=new LinkedHashSet<>();
        for(var f:snapshot.files())if(f.language().equals("Java")){
            Matcher m=IMPORT.matcher(f.source());while(m.find()){
                String imp=m.group(1);
                for(var e:names.entrySet())if(!e.getValue().equals(f.path()) && (imp.equals(e.getKey()) || imp.startsWith(e.getKey()+".") || (imp.endsWith(".*")&&e.getKey().startsWith(imp.substring(0,imp.length()-1)))))
                    edges.add(new Edge(f.path(),e.getValue(),"Java import heuristic",.8));
            }
        }
        return List.copyOf(edges);
    }
    public static Set<String> reach(List<Edge> edges,String start,int depth,boolean reverse){
        Set<String> seen=new LinkedHashSet<>();seen.add(start);Set<String> frontier=Set.of(start);
        for(int i=0;i<depth;i++){Set<String> next=new LinkedHashSet<>();for(Edge e:edges){String a=reverse?e.target():e.source(),b=reverse?e.source():e.target();if(frontier.contains(a)&&seen.add(b))next.add(b);}frontier=next;}
        seen.remove(start);return seen;
    }
    public static Set<String> cycles(List<Edge> edges){
        Set<String> cycle=new HashSet<>();for(Edge e:edges)if(reach(edges,e.target(),edges.size()+1,false).contains(e.source())||e.source().equals(e.target())){cycle.add(e.source());cycle.add(e.target());}return cycle;
    }
}
