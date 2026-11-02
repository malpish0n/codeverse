package dev.codeverse.model;

import java.util.*;
import dev.codeverse.model.Data.Cell;

/** Reserved cells never move while the same repository is open. */
public final class StableLayout {
    private final Map<String,Cell> cells = new LinkedHashMap<>();
    private final Map<String,Integer> modules = new LinkedHashMap<>();
    private final Map<String,Integer> counts = new HashMap<>();
    public Map<String,Cell> reserve(Collection<String> paths) {
        paths.stream().sorted().forEach(path -> cells.computeIfAbsent(path, p -> {
            String module = Data.module(p);
            int m = modules.computeIfAbsent(module, k -> modules.size());
            int n = counts.merge(module, 1, Integer::sum) - 1;
            // Each module receives a square sector; overflow is placed in a new sector.
            int sector = m + (n / 64) * 4096;
            int[] xy = spiral(sector);
            return new Cell(p, xy[0]*220 + (n%8)*23, xy[1]*220 + ((n%64)/8)*23, 16, m);
        }));
        return Map.copyOf(cells);
    }
    public Map<String,Cell> cells() { return Map.copyOf(cells); }
    public void clear() { cells.clear(); modules.clear(); counts.clear(); }
    private static int[] spiral(int n) {
        if(n==0) return new int[]{0,0};
        int k=(int)Math.ceil((Math.sqrt(n+1)-1)/2), t=2*k, m=(2*k+1)*(2*k+1)-1;
        if(n>=m-t) return new int[]{k-(m-n),-k}; m-=t;
        if(n>=m-t) return new int[]{-k,-k+(m-n)}; m-=t;
        if(n>=m-t) return new int[]{-k+(m-n),k};
        return new int[]{k,k-(m-t-n)};
    }
}
