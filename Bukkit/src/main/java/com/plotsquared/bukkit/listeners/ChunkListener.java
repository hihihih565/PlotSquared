package com.plotsquared.bukkit.listeners;

import com.intellectualcrafters.plot.PS;
import com.intellectualcrafters.plot.config.Settings;
import com.intellectualcrafters.plot.object.Location;
import com.intellectualcrafters.plot.object.Plot;
import com.intellectualcrafters.plot.util.ReflectionUtils.RefClass;
import com.intellectualcrafters.plot.util.ReflectionUtils.RefField;
import com.intellectualcrafters.plot.util.ReflectionUtils.RefMethod;
import com.intellectualcrafters.plot.util.TaskManager;
import java.lang.reflect.Method;
import java.util.HashSet;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;


import static com.intellectualcrafters.plot.util.ReflectionUtils.getRefClass;

public class ChunkListener implements Listener {

    private RefMethod methodGetHandleChunk;
    private RefField mustSave;
    private Chunk lastChunk;

    
    public ChunkListener() {
        if (Settings.CHUNK_PROCESSOR_GC) {
            try {
                RefClass classChunk = getRefClass("{nms}.Chunk");
                RefClass classCraftChunk = getRefClass("{cb}.CraftChunk");
                this.mustSave = classChunk.getField("mustSave");
                this.methodGetHandleChunk = classCraftChunk.getMethod("getHandle");
            } catch (Throwable ignored) {
                PS.debug("PlotSquared/Server not compatible for chunk processor trim/gc");
                Settings.CHUNK_PROCESSOR_GC = false;
            }
        }
        if (!Settings.CHUNK_PROCESSOR_GC) {
            return;
        }
        for (World world : Bukkit.getWorlds()) {
            world.setAutoSave(false);
        }
        TaskManager.runTaskRepeat(new Runnable() {
            @Override
            public void run() {
                try {
                    HashSet<Chunk> toUnload = new HashSet<>();
                    for (World world : Bukkit.getWorlds()) {
                        String worldName = world.getName();
                        if (!PS.get().hasPlotArea(worldName)) {
                            continue;
                        }
                        Object w = world.getClass().getDeclaredMethod("getHandle").invoke(world);
                        Object chunkMap = w.getClass().getDeclaredMethod("getPlayerChunkMap").invoke(w);
                        Method methodIsChunkInUse = chunkMap.getClass().getDeclaredMethod("isChunkInUse", int.class, int.class);
                        Chunk[] chunks = world.getLoadedChunks();
                        for (Chunk chunk : chunks) {
                            if ((boolean) methodIsChunkInUse.invoke(chunkMap, chunk.getX(), chunk.getZ())) {
                                continue;
                            }
                            int x = chunk.getX();
                            int z = chunk.getZ();
                            if (!shouldSave(worldName, x, z)) {
                                unloadChunk(worldName, chunk, false);
                                continue;
                            }
                            toUnload.add(chunk);
                        }
                    }
                    if (toUnload.isEmpty()) {
                        return;
                    }
                    long start = System.currentTimeMillis();
                    for (Chunk chunk : toUnload) {
                        if (System.currentTimeMillis() - start > 5) {
                            return;
                        }
                        chunk.unload(true, false);
                    }
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            }
        }, 1);
    }

    public boolean unloadChunk(String world, Chunk chunk, boolean safe) {
        if (safe && shouldSave(world, chunk.getX(), chunk.getZ())) {
            return false;
        }
        Object c = this.methodGetHandleChunk.of(chunk).call();
        this.mustSave.of(c).set(false);
        if (chunk.isLoaded()) {
            chunk.unload(false, false);
        }
        return true;
    }

    public boolean shouldSave(String world, int X, int Z) {
        int x = X << 4;
        int z = Z << 4;
        int x2 = x + 15;
        int z2 = z + 15;
        Plot plot = new Location(world, x, 1, z).getOwnedPlotAbs();
        if (plot != null && plot.hasOwner()) {
            return true;
        }
        plot = new Location(world, x2, 1, z2).getOwnedPlotAbs();
        if (plot != null && plot.hasOwner()) {
            return true;
        }
        plot = new Location(world, x2, 1, z).getOwnedPlotAbs();
        if (plot != null && plot.hasOwner()) {
            return true;
        }
        plot = new Location(world, x, 1, z2).getOwnedPlotAbs();
        if (plot != null && plot.hasOwner()) {
            return true;
        }
        plot = new Location(world, x + 7, 1, z + 7).getOwnedPlotAbs();
        if (plot != null && plot.hasOwner()) {
            return true;
        }
        return false;
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        if (Settings.CHUNK_PROCESSOR_GC) {
            Chunk chunk = event.getChunk();
            String world = chunk.getWorld().getName();
            if (PS.get().hasPlotArea(world)) {
                if (unloadChunk(world, chunk, true)) {
                    return;
                }
            }
        }
        if (processChunk(event.getChunk(), true)) {
            event.setCancelled(true);
        }
    }
    
    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        processChunk(event.getChunk(), false);
    }
    
    @EventHandler(priority = EventPriority.LOWEST)
    public void onItemSpawn(ItemSpawnEvent event) {
        Item entity = event.getEntity();
        Chunk chunk = entity.getLocation().getChunk();
        if (chunk == this.lastChunk) {
            event.getEntity().remove();
            event.setCancelled(true);
            return;
        }
        if (!PS.get().hasPlotArea(chunk.getWorld().getName())) {
            return;
        }
        Entity[] entities = chunk.getEntities();
        if (entities.length > Settings.CHUNK_PROCESSOR_MAX_ENTITIES) {
            event.getEntity().remove();
            event.setCancelled(true);
            this.lastChunk = chunk;
        } else {
            this.lastChunk = null;
        }
    }
    
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPhysics(BlockPhysicsEvent event) {
        if (Settings.CHUNK_PROCESSOR_DISABLE_PHYSICS) {
            event.setCancelled(true);
        }
    }
    
    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntitySpawn(CreatureSpawnEvent event) {
        LivingEntity entity = event.getEntity();
        Chunk chunk = entity.getLocation().getChunk();
        if (chunk == this.lastChunk) {
            event.getEntity().remove();
            event.setCancelled(true);
            return;
        }
        if (!PS.get().hasPlotArea(chunk.getWorld().getName())) {
            return;
        }
        Entity[] entities = chunk.getEntities();
        if (entities.length > Settings.CHUNK_PROCESSOR_MAX_ENTITIES) {
            event.getEntity().remove();
            event.setCancelled(true);
            this.lastChunk = chunk;
        } else {
            this.lastChunk = null;
        }
    }

    private void cleanChunk(final Chunk chunk) {
        TaskManager.index.incrementAndGet();
        final Integer currentIndex = TaskManager.index.get();
        Integer task = TaskManager.runTaskRepeat(new Runnable() {
            @Override
            public void run() {
                if (!chunk.isLoaded()) {
                    Bukkit.getScheduler().cancelTask(TaskManager.tasks.get(currentIndex));
                    TaskManager.tasks.remove(currentIndex);
                    PS.debug("[PlotSquared] &aSuccessfully processed and unloaded chunk!");
                    chunk.unload(true, true);
                    return;
                }
                BlockState[] tiles = chunk.getTileEntities();
                if (tiles.length == 0) {
                    Bukkit.getScheduler().cancelTask(TaskManager.tasks.get(currentIndex));
                    TaskManager.tasks.remove(currentIndex);
                    PS.debug("[PlotSquared] &aSuccessfully processed and unloaded chunk!");
                    chunk.unload(true, true);
                    return;
                }
                long start = System.currentTimeMillis();
                int i = 0;
                while (System.currentTimeMillis() - start < 250) {
                    if (i >= tiles.length) {
                        Bukkit.getScheduler().cancelTask(TaskManager.tasks.get(currentIndex));
                        TaskManager.tasks.remove(currentIndex);
                        PS.debug("[PlotSquared] &aSuccessfully processed and unloaded chunk!");
                        chunk.unload(true, true);
                        return;
                    }
                    tiles[i].getBlock().setType(Material.AIR, false);
                    i++;
                }
            }
        }, 5);
        TaskManager.tasks.put(currentIndex, task);
    }

    public boolean processChunk(Chunk chunk, boolean unload) {
        if (!PS.get().hasPlotArea(chunk.getWorld().getName())) {
            return false;
        }
        Entity[] entities = chunk.getEntities();
        BlockState[] tiles = chunk.getTileEntities();
        if (entities.length > Settings.CHUNK_PROCESSOR_MAX_ENTITIES) {
            for (Entity ent : entities) {
                if (!(ent instanceof Player)) {
                    ent.remove();
                }
            }
            PS.debug("[PlotSquared] &a detected unsafe chunk and processed: " + (chunk.getX() << 4) + "," + (chunk.getX() << 4));
        }
        if (tiles.length > Settings.CHUNK_PROCESSOR_MAX_BLOCKSTATES) {
            if (unload) {
                PS.debug("[PlotSquared] &c detected unsafe chunk: " + (chunk.getX() << 4) + "," + (chunk.getX() << 4));
                cleanChunk(chunk);
                return true;
            }
            for (BlockState tile : tiles) {
                tile.getBlock().setType(Material.AIR, false);
            }
        }
        return false;
    }
}
