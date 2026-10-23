package dev.mcagent.path;

import java.util.ArrayList;
import java.util.List;

/**
 * Movement graph for a standing player. No mining, no placing, no doors.
 */
public final class MoveRules {
    public record Edge(int x, int y, int z, double cost, String kind) {
    }

    private static final int[][] CARDINAL = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] DIAGONAL = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private MoveRules() {
    }

    public static List<Edge> edges(BlockGrid grid, int x, int y, int z) {
        List<Edge> out = new ArrayList<>();
        for (int[] dir : CARDINAL) {
            int nx = x + dir[0];
            int nz = z + dir[1];
            for (int dy : new int[]{0, 1, -1}) {
                int ny = y + dy;
                if (grid.standable(nx, ny, nz)) {
                    double cost = dy == 0 ? 1.0 : (dy > 0 ? 1.3 : 1.2);
                    out.add(new Edge(nx, ny, nz, cost + hazardPenalty(grid, nx, ny, nz), "walk"));
                }
            }
            // fall 2-3 blocks
            for (int drop = 2; drop <= 3; drop++) {
                int ny = y - drop;
                if (grid.standable(nx, ny, nz)) {
                    out.add(new Edge(nx, ny, nz, 1.0 + 0.5 * drop + hazardPenalty(grid, nx, ny, nz), "drop"));
                    break;
                }
            }
            // jump one-block gap
            int jx = x + dir[0] * 2;
            int jz = z + dir[1] * 2;
            int mx = x + dir[0];
            int mz = z + dir[1];
            if (grid.passable(mx, y, mz) && grid.passable(mx, y + 1, mz)
                    && grid.standable(jx, y, jz)) {
                out.add(new Edge(jx, y, jz, 1.3 + hazardPenalty(grid, jx, y, jz), "jump"));
            }
        }
        for (int[] dir : DIAGONAL) {
            int nx = x + dir[0];
            int nz = z + dir[1];
            if (!clearCorner(grid, x, y, z, dir[0], dir[1])) {
                continue;
            }
            for (int dy : new int[]{0, 1, -1}) {
                int ny = y + dy;
                if (grid.standable(nx, ny, nz)) {
                    out.add(new Edge(nx, ny, nz, 1.414 + hazardPenalty(grid, nx, ny, nz), "diagonal"));
                }
            }
        }
        return out;
    }

    private static boolean clearCorner(BlockGrid grid, int x, int y, int z, int dx, int dz) {
        for (int dy : new int[]{0, 1, -1}) {
            if (grid.standable(x + dx, y + dy, z) && grid.standable(x, y + dy, z + dz)) {
                return true;
            }
        }
        return false;
    }

    private static double hazardPenalty(BlockGrid grid, int x, int y, int z) {
        for (int[] dir : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            if (grid.hazard(x + dir[0], y, z + dir[1])
                    || grid.hazard(x + dir[0], y - 1, z + dir[1])) {
                return 2.0;
            }
        }
        return 0.0;
    }

    public static double heuristic(int x, int y, int z, int gx, int gy, int gz) {
        int dx = Math.abs(x - gx);
        int dz = Math.abs(z - gz);
        int dy = Math.abs(y - gy);
        double octile = Math.max(dx, dz) + (Math.sqrt(2) - 1) * Math.min(dx, dz);
        return octile + dy * 1.2;
    }
}
