/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.world;

import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.GoalXZ;
import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.gui.widgets.pressable.WMinus;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.MeteorToast;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.entity.*;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.DispenserBlockEntity;
import net.minecraft.block.entity.EnderChestBlockEntity;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.item.Items;
import net.minecraft.util.math.ChunkPos;

import java.io.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public class StashFinder extends Module {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<List<BlockEntityType<?>>> storageBlocks = sgGeneral.add(new StorageBlockListSetting.Builder()
        .name("storage-blocks")
        .description("Select the storage blocks to search for.")
        .defaultValue(StorageBlockListSetting.STORAGE_BLOCKS)
        .build()
    );

    private final Setting<Integer> minimumStorageCount = sgGeneral.add(new IntSetting.Builder()
        .name("minimum-storage-count")
        .description("The minimum amount of storage blocks in a chunk to record the chunk.")
        .defaultValue(4)
        .min(1)
        .sliderMin(1)
        .build()
    );

    private final Setting<Integer> minimumDistance = sgGeneral.add(new IntSetting.Builder()
        .name("minimum-distance")
        .description("The minimum distance you must be from spawn to record a certain chunk.")
        .defaultValue(0)
        .min(0)
        .sliderMax(10000)
        .build()
    );

    private final Setting<Boolean> sendNotifications = sgGeneral.add(new BoolSetting.Builder()
        .name("notifications")
        .description("Sends Minecraft notifications when new stashes are found.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Mode> notificationMode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("notification-mode")
        .description("The mode to use for notifications.")
        .defaultValue(Mode.Both)
        .visible(sendNotifications::get)
        .build()
    );

    public List<Chunk> chunks = new ArrayList<>();

    public StashFinder() {
        super(Categories.World, "stash-finder", "Finds chunks with storage blocks.");
    }

    @Override
    public void onActivate() {
        chunks.clear();
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();

        WTable table = list.add(theme.table()).expandX().widget();

        table.add(theme.label("Chunk"));
        table.add(theme.label("Storages"));

        chunks.sort(Comparator.comparingInt(Chunk::getStorageCount).reversed());

        for (Chunk chunk : chunks) {
            WButton button = table.add(theme.button(chunk.getPos().toShortString())).expandCellX().widget();
            button.action = () -> {
                BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoalAndPath(new GoalXZ(chunk.getPos().x, chunk.getPos().z));
                Utils.mc.setScreen(new WindowScreen(new ChunkScreen(chunk, theme)));
            };

            table.add(theme.label(String.valueOf(chunk.getStorageCount())));
        }

        list.add(theme.horizontalSeparator());

        WMinus minus = list.add(theme.minus()).widget();
        minus.action = () -> {
            chunks.clear();
            MeteorToast.showToast(theme, "Cleared stashes.", MeteorToast.Type.Info);
        };

        return list;
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        if (!event.shouldLoad()) return;

        ChunkPos pos = event.chunk.getPos();
        double distanceToSpawn = Math.sqrt(pos.x * pos.x + pos.z * pos.z);

        if (distanceToSpawn < minimumDistance.get()) return;

        int storageCount = countStorageBlocks(event.chunk);

        if (storageCount >= minimumStorageCount.get()) {
            Chunk chunk = new Chunk(pos, storageCount);

            if (!chunks.contains(chunk)) {
                chunks.add(chunk);
                chunks.sort(Comparator.comparingInt(Chunk::getStorageCount).reversed());

                if (sendNotifications.get()) {
                    String message = "Found chunk " + chunk.getPos().toShortString() + " with " + chunk.getStorageCount() + " storage blocks.";

                    if (notificationMode.get() == Mode.Chat || notificationMode.get() == Mode.Both) {
                        info(message);
                    }

                    if (notificationMode.get() == Mode.Notification || notificationMode.get() == Mode.Both) {
                        MeteorClient.toast().add(new MeteorToast.Notification("Stash Finder", message));
                    }
                }
            }
        }
    }

    private int countStorageBlocks(net.minecraft.world.chunk.WorldChunk chunk) {
        int count = 0;

        for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
            if (storageBlocks.get().contains(blockEntity.getType())) {
                count++;
            }
        }

        return count;
    }

    @Override
    public void read(File file) throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            chunks = GSON.fromJson(reader, new TypeToken<List<Chunk>>() {}.getType());
        }
    }

    @Override
    public void write(File file) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            writer.write(GSON.toJson(chunks));
        }
    }

    public static class Chunk {
        private final ChunkPos pos;
        private final int storageCount;

        public Chunk(ChunkPos pos, int storageCount) {
            this.pos = pos;
            this.storageCount = storageCount;
        }

        public ChunkPos getPos() {
            return pos;
        }

        public int getStorageCount() {
            return storageCount;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Chunk chunk = (Chunk) o;
            return Objects.equals(pos, chunk.pos);
        }

        @Override
        public int hashCode() {
            return Objects.hash(pos);
        }
    }

    public enum Mode {
        Chat,
        Notification,
        Both
    }
}

