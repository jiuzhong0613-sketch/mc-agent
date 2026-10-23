package dev.mcagent.plan;

import com.google.gson.JsonObject;
import dev.mcagent.ClientThread;
import dev.mcagent.query.QueryRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

public final class McFactSource implements FactSource {
    @Override
    public int inventoryCount(String item) {
        try {
            return ClientThread.call(() -> {
                var inv = Minecraft.getInstance().player.getInventory();
                int count = 0;
                for (int i = 0; i < inv.getContainerSize(); i++) {
                    ItemStack stack = inv.getItem(i);
                    if (!stack.isEmpty()
                            && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(item)) {
                        count += stack.getCount();
                    }
                }
                return count;
            }, 2000);
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public String blockAt(int x, int y, int z) {
        try {
            return ClientThread.call(() -> {
                Minecraft mc = Minecraft.getInstance();
                return BuiltInRegistries.BLOCK.getKey(
                        mc.level.getBlockState(new BlockPos(x, y, z)).getBlock()).toString();
            }, 2000);
        } catch (Exception e) {
            return "error";
        }
    }

    @Override
    public int blocksCount(String blockId, int radius) {
        try {
            return ClientThread.call(() -> {
                Minecraft mc = Minecraft.getInstance();
                BlockPos center = mc.player.blockPosition();
                int count = 0;
                int visited = 0;
                for (int x = -radius; x <= radius; x++) {
                    for (int y = -radius; y <= radius; y++) {
                        for (int z = -radius; z <= radius; z++) {
                            if (++visited > 8192) {
                                return count;
                            }
                            BlockPos p = center.offset(x, y, z);
                            if (!mc.level.isLoaded(p)) {
                                continue;
                            }
                            if (BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(p).getBlock())
                                    .toString().equals(blockId)) {
                                count++;
                            }
                        }
                    }
                }
                return count;
            }, 3000);
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public boolean positionNear(double x, double y, double z, double tolerance) {
        try {
            return ClientThread.call(() -> {
                Minecraft mc = Minecraft.getInstance();
                return mc.player.position().distanceTo(new net.minecraft.world.phys.Vec3(x, y, z)) <= tolerance;
            }, 2000);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public int entityCount(String type, int radius) {
        try {
            return ClientThread.call(() -> {
                Minecraft mc = Minecraft.getInstance();
                int count = 0;
                for (Entity e : mc.level.entitiesForRendering()) {
                    if (e == mc.player || e.distanceTo(mc.player) > radius) {
                        continue;
                    }
                    if (BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString().equals(type)) {
                        count++;
                    }
                }
                return count;
            }, 2000);
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public JsonObject query(String name, JsonObject args) throws Exception {
        return QueryRegistry.run(name, args, false);
    }
}
