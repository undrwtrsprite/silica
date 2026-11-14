package dev.betavk.hooks;

import net.minecraft.src.ChunkCoordinates;
import net.minecraft.src.EntityPlayer;
import net.minecraft.src.EnumStatus;

/** Records a bed spawn when a player uses a bed, even during the day. */
public final class Beds {
    private Beds() {
    }

    public static EnumStatus use(EntityPlayer player, int x, int y, int z) {
        EnumStatus result = player.sleepInBedAt(x, y, z);
        // The block interaction has already checked reach. Sleeping has its own shorter range.
        if (result == EnumStatus.OK || result == EnumStatus.NOT_POSSIBLE_NOW || result == EnumStatus.TOO_FAR_AWAY) {
            ChunkCoordinates previous = player.getPlayerSpawnCoordinate();
            if (previous == null || previous.x != x || previous.y != y || previous.z != z) {
                player.setPlayerSpawnCoordinate(new ChunkCoordinates(x, y, z));
                if (result != EnumStatus.OK) {
                    player.addChatMessage("Respawn point set");
                }
            }
        }
        return result;
    }
}
