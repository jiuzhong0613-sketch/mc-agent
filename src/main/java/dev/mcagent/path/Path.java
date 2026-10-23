package dev.mcagent.path;

import java.util.List;

public final class Path {
    public enum Status {
        FOUND, PARTIAL, UNREACHABLE, BUDGET, INVALID
    }

    public final Status status;
    public final List<int[]> nodes;
    public final String reason;
    public final int expanded;

    public Path(Status status, List<int[]> nodes, String reason, int expanded) {
        this.status = status;
        this.nodes = nodes;
        this.reason = reason;
        this.expanded = expanded;
    }

    public boolean reachable() {
        return status == Status.FOUND;
    }
}
