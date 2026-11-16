package dev.betavk.hooks.mesh;

import net.minecraft.src.WorldRenderer;

import java.util.ArrayList;
import java.util.List;

/** Output of meshing one chunk section on a worker: captured draws per render pass plus the flags the renderer needs. */
public final class MeshResult {
    public final WorldRenderer target;
    public final int generation;
    @SuppressWarnings("unchecked")
    public final List<Draw>[] draws = new List[]{new ArrayList<Draw>(), new ArrayList<Draw>()};
    public final List<Draw> cutoutDraws = new ArrayList<>();
    public final boolean[] started = new boolean[2];    // a display list is compiled for this pass
    public final boolean[] rendered = new boolean[2];   // something was drawn: the pass must be rendered
    public final List<int[]> tileEntityPositions = new ArrayList<>();
    public boolean lit;
    public long visMask = CaveCull.ALL_VISIBLE;
    public Throwable failure;
    public long meshNanos;

    public MeshResult(WorldRenderer target, int generation) {
        this.target = target;
        this.generation = generation;
    }
}
