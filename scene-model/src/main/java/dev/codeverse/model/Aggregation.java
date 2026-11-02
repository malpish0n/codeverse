package dev.codeverse.model;

import dev.codeverse.model.Data.*;
import java.util.*;

/** Large scenes retain exact files for inspection but render module aggregates. */
public final class Aggregation {
    public static Snapshot forRendering(Snapshot snapshot){
        if(snapshot.files().size()<=10000)return snapshot;
        Map<String,List<FileRevision>> groups=new TreeMap<>();
        for(var f:snapshot.files()){
            String[] parts=f.path().split("/");String module=parts.length>2?parts[0]+"/"+parts[1]:parts[0];
            groups.computeIfAbsent(module,k->new ArrayList<>()).add(f);
        }
        var files=new ArrayList<FileRevision>();
        for(var e:groups.entrySet()){
            long loc=e.getValue().stream().mapToLong(FileRevision::loc).sum(),bytes=e.getValue().stream().mapToLong(FileRevision::bytes).sum();
            files.add(new FileRevision(e.getKey()+"/@aggregate",snapshot.sha(),(int)Math.min(Integer.MAX_VALUE,loc),bytes,"Aggregate",e.getKey(),e.getValue().size()+" files (aggregated)",""));
        }
        // Pathologically flat repositories are divided into bounded batches.
        if(files.size()>10000){files.clear();var sorted=snapshot.files().stream().sorted(Comparator.comparing(FileRevision::path)).toList();for(int i=0;i<sorted.size();i+=100){var part=sorted.subList(i,Math.min(i+100,sorted.size()));files.add(new FileRevision("batch/"+(i/100),snapshot.sha(),(int)Math.min(Integer.MAX_VALUE,part.stream().mapToLong(FileRevision::loc).sum()),part.stream().mapToLong(FileRevision::bytes).sum(),"Aggregate","batch",part.size()+" files (aggregated)",""));}}
        return new Snapshot(snapshot.sha(),List.copyOf(files));
    }
}
