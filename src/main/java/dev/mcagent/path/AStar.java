package dev.mcagent.path;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;

/**
 * A* over BlockGrid. Pure algorithm: no Minecraft types, unit-testable.
 */
public final class AStar {
    public static final int DEFAULT_MAX_EXPANDED = 50_000;
    public static final int MAX_PATH = 256;

    private record Node(int index, int x, int y, int z, double f) {
    }

    private AStar() {
    }

    public static Path search(BlockGrid grid, int sx, int sy, int sz,
                              int gx, int gy, int gz, int maxExpanded) {
        if (!grid.standable(sx, sy, sz)) {
            return new Path(Path.Status.INVALID, List.of(), "start not standable", 0);
        }
        if (!grid.standable(gx, gy, gz)) {
            return new Path(Path.Status.INVALID, List.of(), "goal not standable", 0);
        }
        int cells = grid.sizeX * grid.sizeY * grid.sizeZ;
        double[] gScore = new double[cells];
        int[] cameFrom = new int[cells];
        boolean[] closed = new boolean[cells];
        Arrays.fill(gScore, Double.POSITIVE_INFINITY);
        Arrays.fill(cameFrom, -1);

        int start = grid.index(sx, sy, sz);
        int goal = grid.index(gx, gy, gz);
        gScore[start] = 0;
        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
        open.add(new Node(start, sx, sy, sz, MoveRules.heuristic(sx, sy, sz, gx, gy, gz)));

        int expanded = 0;
        int best = start;
        double bestH = MoveRules.heuristic(sx, sy, sz, gx, gy, gz);

        while (!open.isEmpty()) {
            Node current = open.poll();
            if (closed[current.index] || current.index >= cells) {
                continue;
            }
            closed[current.index] = true;
            expanded++;
            double h = MoveRules.heuristic(current.x, current.y, current.z, gx, gy, gz);
            if (h < bestH) {
                bestH = h;
                best = current.index;
            }
            if (current.index == goal) {
                return reconstruct(grid, cameFrom, start, goal, Path.Status.FOUND, "found", expanded);
            }
            if (expanded >= maxExpanded) {
                return reconstruct(grid, cameFrom, start, best, Path.Status.BUDGET,
                        "node budget exhausted", expanded);
            }
            for (MoveRules.Edge edge : MoveRules.edges(grid, current.x, current.y, current.z)) {
                int ni = grid.index(edge.x(), edge.y(), edge.z());
                if (closed[ni]) {
                    continue;
                }
                double tentative = gScore[current.index] + edge.cost();
                if (tentative + 1e-9 < gScore[ni]) {
                    gScore[ni] = tentative;
                    cameFrom[ni] = current.index;
                    double f = tentative + MoveRules.heuristic(edge.x(), edge.y(), edge.z(), gx, gy, gz);
                    open.add(new Node(ni, edge.x(), edge.y(), edge.z(), f));
                }
            }
        }
        return reconstruct(grid, cameFrom, start, best, Path.Status.UNREACHABLE,
                "no route", expanded);
    }

    private static Path reconstruct(BlockGrid grid, int[] cameFrom, int start, int end,
                                    Path.Status status, String reason, int expanded) {
        List<int[]> nodes = new ArrayList<>();
        int cursor = end;
        int guard = 0;
        while (cursor != -1 && guard++ < MAX_PATH * 4) {
            int y = cursor / (grid.sizeZ * grid.sizeX) + grid.minY;
            int rem = cursor % (grid.sizeZ * grid.sizeX);
            int z = rem / grid.sizeX + grid.minZ;
            int x = rem % grid.sizeX + grid.minX;
            nodes.add(0, new int[]{x, y, z});
            if (cursor == start) {
                break;
            }
            cursor = cameFrom[cursor];
        }
        if (nodes.size() > MAX_PATH) {
            nodes = new ArrayList<>(nodes.subList(0, MAX_PATH));
            status = Path.Status.PARTIAL;
            reason = "path too long";
        }
        return new Path(status, nodes, reason, expanded);
    }
}
