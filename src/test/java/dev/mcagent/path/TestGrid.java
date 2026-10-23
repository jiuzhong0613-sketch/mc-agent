package dev.mcagent.path;

final class TestGrid {
    private TestGrid() {
    }

    static BlockGrid flat(int radius) {
        BlockGrid grid = new BlockGrid(-radius, -6, -radius, radius * 2 + 1, 12, radius * 2 + 1);
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                grid.set(x, -1, z, BlockGrid.SOLID);
                grid.set(x, 0, z, BlockGrid.PASSABLE);
                grid.set(x, 1, z, BlockGrid.PASSABLE);
                grid.set(x, 2, z, BlockGrid.PASSABLE);
            }
        }
        return grid;
    }
}
