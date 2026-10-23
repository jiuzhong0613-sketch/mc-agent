package dev.mcagent.path;

/**
 * Bounded 3D grid of block flags. Pure data, no Minecraft types.
 */
public final class BlockGrid {
    public static final byte PASSABLE = 1;
    public static final byte SOLID = 2;
    public static final byte FLUID = 4;
    public static final byte HAZARD = 8;
    public static final byte UNKNOWN = 16;

    public final int minX;
    public final int minY;
    public final int minZ;
    public final int sizeX;
    public final int sizeY;
    public final int sizeZ;
    private final byte[] flags;

    public BlockGrid(int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.flags = new byte[sizeX * sizeY * sizeZ];
    }

    public BlockGrid(int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ, byte[] flags) {
        this(minX, minY, minZ, sizeX, sizeY, sizeZ);
        if (flags.length != this.flags.length) {
            throw new IllegalArgumentException("flag array size mismatch");
        }
        System.arraycopy(flags, 0, this.flags, 0, flags.length);
    }

    public boolean inBounds(int x, int y, int z) {
        return x >= minX && x < minX + sizeX
                && y >= minY && y < minY + sizeY
                && z >= minZ && z < minZ + sizeZ;
    }

    public int index(int x, int y, int z) {
        return ((y - minY) * sizeZ + (z - minZ)) * sizeX + (x - minX);
    }

    public byte flags(int x, int y, int z) {
        if (!inBounds(x, y, z)) {
            return UNKNOWN;
        }
        return flags[index(x, y, z)];
    }

    public void set(int x, int y, int z, byte value) {
        if (inBounds(x, y, z)) {
            flags[index(x, y, z)] = value;
        }
    }

    public boolean has(int x, int y, int z, byte flag) {
        return (flags(x, y, z) & flag) != 0;
    }

    public boolean passable(int x, int y, int z) {
        return has(x, y, z, PASSABLE);
    }

    public boolean solid(int x, int y, int z) {
        return has(x, y, z, SOLID);
    }

    public boolean fluid(int x, int y, int z) {
        return has(x, y, z, FLUID);
    }

    public boolean hazard(int x, int y, int z) {
        return has(x, y, z, HAZARD);
    }

    public boolean known(int x, int y, int z) {
        return inBounds(x, y, z) && !has(x, y, z, UNKNOWN);
    }

    /** Feet and head free, solid non-fluid support below. */
    public boolean standable(int x, int y, int z) {
        if (!known(x, y, z) || !known(x, y + 1, z) || !known(x, y - 1, z)) {
            return false;
        }
        if (!passable(x, y, z) || !passable(x, y + 1, z)) {
            return false;
        }
        if (hazard(x, y, z) || hazard(x, y + 1, z)) {
            return false;
        }
        return solid(x, y - 1, z) && !fluid(x, y - 1, z) && !hazard(x, y - 1, z);
    }
}
