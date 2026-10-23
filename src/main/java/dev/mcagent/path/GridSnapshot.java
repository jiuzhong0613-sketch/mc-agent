package dev.mcagent.path;

import dev.mcagent.ClientThread;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Copies the loaded client world into a BlockGrid on the client thread.
 */
public final class GridSnapshot {
    private GridSnapshot() {
    }

    public static BlockGrid capture(int radius, int vertical) throws Exception {
        return ClientThread.call(() -> captureNow(radius, vertical), 4000);
    }

    private static BlockGrid captureNow(int radius, int vertical) {
        Minecraft mc = Minecraft.getInstance();
        BlockPos center = mc.player.blockPosition();
        int sizeX = radius * 2 + 1;
        int sizeY = vertical * 2 + 1;
        int sizeZ = radius * 2 + 1;
        BlockGrid grid = new BlockGrid(center.getX() - radius, center.getY() - vertical,
                center.getZ() - radius, sizeX, sizeY, sizeZ);
        for (int x = 0; x < sizeX; x++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int y = 0; y < sizeY; y++) {
                    BlockPos pos = new BlockPos(grid.minX + x, grid.minY + y, grid.minZ + z);
                    if (!mc.level.isLoaded(pos)) {
                        grid.set(pos.getX(), pos.getY(), pos.getZ(), BlockGrid.UNKNOWN);
                        continue;
                    }
                    BlockState state = mc.level.getBlockState(pos);
                    boolean solid = state.isSolid();
                    boolean fluid = !state.getFluidState().isEmpty();
                    boolean hazard = isHazard(state);
                    byte flags = 0;
                    if (solid) {
                        flags |= BlockGrid.SOLID;
                    }
                    if (fluid) {
                        flags |= BlockGrid.FLUID;
                    }
                    if (hazard) {
                        flags |= BlockGrid.HAZARD;
                    }
                    if (!solid && !fluid && !hazard) {
                        flags |= BlockGrid.PASSABLE;
                    }
                    grid.set(pos.getX(), pos.getY(), pos.getZ(), flags);
                }
            }
        }
        return grid;
    }

    private static boolean isHazard(BlockState state) {
        return state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)
                || state.is(Blocks.LAVA) || state.is(Blocks.MAGMA_BLOCK)
                || state.is(Blocks.CACTUS) || state.is(Blocks.SWEET_BERRY_BUSH)
                || state.is(Blocks.POWDER_SNOW);
    }
}
