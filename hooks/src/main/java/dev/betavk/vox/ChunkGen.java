package dev.betavk.vox;

import net.minecraft.src.ChunkProviderGenerate;
import net.minecraft.src.World;
import net.minecraft.src.WorldChunkManager;

/** Terrain generation on a worker: the game's own generator (noise, biome surface, caves) against a private stand-in world. One per worker thread. */
final class ChunkGen {
    final GenWorld world;
    final ChunkProviderGenerate provider;

    ChunkGen(World real, long seed) {
        world = GenWorld.create(real, seed);
        provider = new ChunkProviderGenerate(world, seed);
    }

    ColumnData generate(int cx, int cz) {
        // the generator's own steps, without the Chunk object (its lighting reaches into neighbouring chunks through the world)
        byte[] blocks = new byte[32768];
        provider.rand.setSeed((long) cx * 341873128712L + (long) cz * 132897987541L);
        WorldChunkManager m = world.manager();
        provider.biomesForGeneration = m.loadBlockGeneratorData(provider.biomesForGeneration, cx * 16, cz * 16, 16, 16);
        provider.generateTerrain(cx, cz, blocks, provider.biomesForGeneration, m.temperature);
        provider.replaceBlocksForBiome(cx, cz, blocks, provider.biomesForGeneration);
        provider.field_902_u.func_867_a(provider, world, cx, cz, blocks);   // caves
        ColumnData d = new ColumnData(cx, cz, blocks, new byte[32768]);
        for (int i = 0; i < 256; i++) {
            d.temp[i] = (float) m.temperature[i];
            d.hum[i] = (float) m.humidity[i];
        }
        d.corner = provider.biomesForGeneration[0];
        d.summarise();
        return d;
    }
}
