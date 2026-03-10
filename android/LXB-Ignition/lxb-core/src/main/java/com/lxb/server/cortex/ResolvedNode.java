package com.lxb.server.cortex;

public class ResolvedNode {
    public final Bounds bounds;
    public final int candidateCount;
    public final String pickedStage;

    public ResolvedNode(Bounds bounds, int candidateCount, String pickedStage) {
        this.bounds = bounds;
        this.candidateCount = candidateCount;
        this.pickedStage = pickedStage;
    }
}

