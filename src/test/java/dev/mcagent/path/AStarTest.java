package dev.mcagent.path;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AStarTest {
    @Test
    void findsPathOnFlatGround() {
        BlockGrid grid = TestGrid.flat(8);
        Path path = AStar.search(grid, 0, 0, 0, 5, 0, 0, AStar.DEFAULT_MAX_EXPANDED);
        assertTrue(path.reachable(), path.reason);
        int[] last = path.nodes.get(path.nodes.size() - 1);
        assertEquals(5, last[0]);
        assertEquals(0, last[1]);
        assertEquals(0, last[2]);
    }

    @Test
    void walksAroundWall() {
        BlockGrid grid = TestGrid.flat(10);
        for (int z = -5; z <= 5; z++) {
            grid.set(2, 0, z, BlockGrid.SOLID);
            grid.set(2, 1, z, BlockGrid.SOLID);
        }
        Path path = AStar.search(grid, 0, 0, 0, 5, 0, 0, AStar.DEFAULT_MAX_EXPANDED);
        assertTrue(path.reachable(), path.reason);
        for (int[] node : path.nodes) {
            assertFalse(node[0] == 2 && Math.abs(node[2]) <= 5, "path crossed the wall");
        }
    }

    @Test
    void jumpsOneBlockGap() {
        BlockGrid grid = TestGrid.flat(8);
        grid.set(2, -1, 0, (byte) 0);
        Path path = AStar.search(grid, 0, 0, 0, 4, 0, 0, AStar.DEFAULT_MAX_EXPANDED);
        assertTrue(path.reachable(), path.reason);
        boolean usedJump = path.nodes.stream().anyMatch(n -> n[0] == 3 && n[1] == 0);
        assertTrue(usedJump, "expected a jump over the gap");
    }

    @Test
    void waterStripIsImpassable() {
        BlockGrid grid = TestGrid.flat(10);
        for (int z = -10; z <= 10; z++) {
            grid.set(2, 0, z, BlockGrid.FLUID);
            grid.set(2, 1, z, BlockGrid.FLUID);
        }
        Path path = AStar.search(grid, 0, 0, 0, 5, 0, 0, AStar.DEFAULT_MAX_EXPANDED);
        assertFalse(path.reachable());
        assertTrue(path.status == Path.Status.UNREACHABLE || path.status == Path.Status.PARTIAL);
    }

    @Test
    void dropsThreeButNotFour() {
        BlockGrid three = TestGrid.flat(10);
        for (int x = 2; x <= 8; x++) {
            for (int z = -10; z <= 10; z++) {
                three.set(x, -1, z, (byte) 0);
                three.set(x, 0, z, BlockGrid.PASSABLE);
                three.set(x, 1, z, BlockGrid.PASSABLE);
                three.set(x, -4, z, BlockGrid.SOLID);
                three.set(x, -3, z, BlockGrid.PASSABLE);
                three.set(x, -2, z, BlockGrid.PASSABLE);
            }
        }
        Path ok = AStar.search(three, 0, 0, 0, 5, -3, 0, AStar.DEFAULT_MAX_EXPANDED);
        assertTrue(ok.reachable(), ok.reason);

        BlockGrid four = TestGrid.flat(10);
        for (int x = 2; x <= 8; x++) {
            for (int z = -10; z <= 10; z++) {
                four.set(x, -1, z, (byte) 0);
                four.set(x, 0, z, BlockGrid.PASSABLE);
                four.set(x, 1, z, BlockGrid.PASSABLE);
                four.set(x, -5, z, BlockGrid.SOLID);
                four.set(x, -4, z, BlockGrid.PASSABLE);
                four.set(x, -3, z, BlockGrid.PASSABLE);
            }
        }
        Path no = AStar.search(four, 0, 0, 0, 5, -4, 0, AStar.DEFAULT_MAX_EXPANDED);
        assertFalse(no.reachable());
    }

    @Test
    void respectsNodeBudget() {
        BlockGrid grid = TestGrid.flat(20);
        Path path = AStar.search(grid, 0, 0, 0, 15, 0, 15, 1);
        assertFalse(path.reachable());
        assertEquals(Path.Status.BUDGET, path.status);
    }

    @Test
    void rejectsUnstandableGoal() {
        BlockGrid grid = TestGrid.flat(5);
        grid.set(3, 0, 0, BlockGrid.SOLID);
        Path path = AStar.search(grid, 0, 0, 0, 3, 0, 0, AStar.DEFAULT_MAX_EXPANDED);
        assertEquals(Path.Status.INVALID, path.status);
    }

    @Test
    void pathIsContiguous() {
        BlockGrid grid = TestGrid.flat(8);
        Path path = AStar.search(grid, 0, 0, 0, 6, 0, 3, AStar.DEFAULT_MAX_EXPANDED);
        assertTrue(path.reachable(), path.reason);
        List<int[]> nodes = path.nodes;
        for (int i = 1; i < nodes.size(); i++) {
            int[] a = nodes.get(i - 1);
            int[] b = nodes.get(i);
            int dx = Math.abs(a[0] - b[0]);
            int dz = Math.abs(a[2] - b[2]);
            int dy = Math.abs(a[1] - b[1]);
            assertTrue(dx <= 2 && dz <= 2 && dy <= 3);
        }
    }
}
