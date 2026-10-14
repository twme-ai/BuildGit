// MIT; signatures from Moulberry/AxiomPaperPlugin. Compile only.
package com.moulberry.axiom.integration;
import org.bukkit.World;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
public final class Integration {
    private Integration() {}
    public interface CustomIntegration {
        boolean canBreakBlock(Player player, Block block);
        boolean canPlaceBlock(Player player, Location location);
        SectionPermissionChecker checkSection(Player player, World world, int x, int y, int z);
    }
    public static void registerCustomIntegration(Plugin plugin, CustomIntegration custom) {
        throw new UnsupportedOperationException("compile-only API");
    }
}
