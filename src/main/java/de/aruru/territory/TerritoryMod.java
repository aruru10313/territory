package de.aruru.territory;

import com.mojang.brigadier.arguments.StringArgumentType;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.markers.ExtrudeMarker;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.math.Color;
import de.bluecolored.bluemap.api.math.Shape;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import org.joml.Vector3f;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;

import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

@Mod(TerritoryMod.MOD_ID)
public final class TerritoryMod {
    public static final String MOD_ID = "territory";
    private static final String DATA_ID = "territory_claims";
    private static final String MARKER_SET_ID = "territories";
    private static final DustParticleOptions BORDER_PARTICLE =
            new DustParticleOptions(new Vector3f(0.15f, 0.55f, 1.0f), 1.5f);
    private static TerritoryMod instance;
    private static final Map<UUID, PendingRemoval> pendingRemovals = new ConcurrentHashMap<>();
    private static final Set<UUID> boundaryViewers = ConcurrentHashMap.newKeySet();
    private static final long REMOVAL_CONFIRMATION_TIMEOUT_MS = 30_000L;

    private MinecraftServer server;
    private TerritoryDatabase database;
    private TerritoryConfig config;
    private int tick;

    public TerritoryMod() {
        instance = this;
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(this);
        BlueMapAPI.onEnable(api -> refreshMarkers(api, server));
    }

    private static TerritoryMod instance() {
        if (instance == null) throw new IllegalStateException("Territory mod is not initialized");
        return instance;
    }

    @SubscribeEvent
    public void registerCommands(RegisterCommandsEvent event) {
        registerCommand(event, "territory");
        registerCommand(event, "trm");
    }

    private static void registerCommand(RegisterCommandsEvent event, String commandName) {
        event.getDispatcher().register(literal(commandName)
                .requires(source -> source.hasPermission(4))
                .executes(ctx -> toggleBoundary(ctx.getSource().getServer(),
                        ctx.getSource().getPlayerOrException()))
                .then(literal("buy")
                        .executes(ctx -> claim(ctx.getSource().getServer(),
                                ctx.getSource().getPlayerOrException(), ""))
                        .then(argument("name", StringArgumentType.greedyString())
                                .executes(ctx -> claim(ctx.getSource().getServer(),
                                        ctx.getSource().getPlayerOrException(),
                                        StringArgumentType.getString(ctx, "name")))))
                .then(literal("remove")
                        .executes(ctx -> unclaim(ctx.getSource().getServer(),
                                ctx.getSource().getPlayerOrException())))
                .then(literal("confirm")
                        .executes(ctx -> confirmUnclaim(ctx.getSource().getServer(),
                                ctx.getSource().getPlayerOrException())))
                .then(literal("cancel")
                        .executes(ctx -> cancelUnclaim(ctx.getSource().getPlayerOrException())))
                .then(literal("show")
                        .executes(ctx -> show(ctx.getSource().getServer(),
                                ctx.getSource().getPlayerOrException())))
                .then(literal("list")
                        .executes(ctx -> list(ctx.getSource().getServer(),
                                ctx.getSource().getPlayerOrException()))));
    }

    @SubscribeEvent
    public void serverStarted(ServerStartedEvent event) {
        server = event.getServer();
        config = TerritoryConfig.load();
        database = TerritoryDatabase.open();
        TerritoryData.get(server).loadDatabase(database);
        TerritoryData.get(server).writeDatabase(database);
        TerritoryData.get(server).writeClaimsFile();
        refreshMarkers(BlueMapAPI.getInstance().orElse(null), server);
    }

    @SubscribeEvent
    public void serverTick(ServerTickEvent.Post event) {
        if (server == null || ++tick < 20) return;
        tick = 0;
        if (database != null && server.getTickCount() % 200 == 0) {
            database.retryRemoteSync();
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            showBoundary(player);
        }
    }

    @SubscribeEvent
    public void serverStopping(ServerStoppingEvent event) {
        if (database != null) {
            database.close();
            database = null;
        }
    }

    private static int claim(MinecraftServer server, ServerPlayer player, String name) {
        if (!instance().config.isEnabled(player.serverLevel().dimension().location().toString())) {
            player.sendSystemMessage(Component.literal("이 월드에서는 영토를 사용할 수 없습니다."));
            return 0;
        }
        ServerLevel level = player.serverLevel();
        TerritoryData data = TerritoryData.get(server);
        ChunkPos chunk = new ChunkPos(player.blockPosition());
        String key = key(level, chunk);
        String owner = name.trim();
        Claim existing = data.claims.get(key);
        if (!owner.isEmpty() && existing != null && existing.ownerUuid.equals(player.getUUID())
                && existing.territoryName.equals(owner)) {
            player.sendSystemMessage(Component.literal("현재 청크는 이미 '" + owner + "' 영토에 포함되어 있습니다."));
            return 0;
        }
        if (existing != null) {
            player.sendSystemMessage(Component.literal("이미 구매된 청크입니다."));
            return 0;
        }
        if (owner.isEmpty()) {
            owner = data.findAdjacentTerritory(level, chunk, player.getUUID());
            if (owner == null) {
                player.sendSystemMessage(Component.literal(
                        "붙어 있는 내 영토가 없습니다. 새 영토를 만들려면 /territory buy <이름>을 사용하세요."));
                return 0;
            }
        }
        if (!data.isAdjacentToTerritory(level, chunk, player.getUUID(), owner)) {
            player.sendSystemMessage(Component.literal(
                    "새 영토 청크는 같은 이름의 기존 영토와 상하좌우로 붙어 있어야 합니다."));
            return 0;
        }
        data.claims.put(key, new Claim(player.getUUID(), player.getName().getString(), owner));
        data.setDirty();
        data.writeDatabase(TerritoryMod.instance().database);
        data.writeClaimsFile();
        player.sendSystemMessage(Component.literal("청크 " + chunk.x + ", " + chunk.z + "를 '" + owner + "' 영토로 등록했습니다."));
        refreshMarkers(BlueMapAPI.getInstance().orElse(null), server);
        return 1;
    }

    private static int unclaim(MinecraftServer server, ServerPlayer player) {
        if (!instance().config.isEnabled(player.serverLevel().dimension().location().toString())) {
            player.sendSystemMessage(Component.literal("이 월드에서는 영토를 사용할 수 없습니다."));
            return 0;
        }
        TerritoryData data = TerritoryData.get(server);
        String claimKey = key(player.serverLevel(), new ChunkPos(player.blockPosition()));
        if (!data.claims.containsKey(claimKey)) {
            player.sendSystemMessage(Component.literal("현재 청크는 구매되어 있지 않습니다."));
            return 0;
        }
        pendingRemovals.put(player.getUUID(), new PendingRemoval(claimKey, System.currentTimeMillis()));
        Component accept = Component.literal("[수락]")
                .withStyle(style -> style
                        .withColor(net.minecraft.ChatFormatting.RED)
                        .withBold(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/territory confirm"))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.literal("현재 청크 영토를 삭제합니다."))));
        Component decline = Component.literal("[거절]")
                .withStyle(style -> style
                        .withColor(net.minecraft.ChatFormatting.GREEN)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/territory cancel"))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.literal("삭제하지 않습니다."))));
        player.sendSystemMessage(Component.literal("현재 청크 영토를 삭제하시겠습니까? ")
                .append(accept).append(Component.literal(" ")).append(decline));
        return 1;
    }

    private static int confirmUnclaim(MinecraftServer server, ServerPlayer player) {
        PendingRemoval pending = pendingRemovals.remove(player.getUUID());
        if (pending == null || System.currentTimeMillis() - pending.createdAt > REMOVAL_CONFIRMATION_TIMEOUT_MS) {
            player.sendSystemMessage(Component.literal("삭제 확인 시간이 만료되었습니다. /territory remove 를 다시 실행하세요."));
            return 0;
        }
        TerritoryData data = TerritoryData.get(server);
        if (data.claims.remove(pending.claimKey) == null) {
            player.sendSystemMessage(Component.literal("해당 영토 청크가 이미 삭제되었습니다."));
            return 0;
        }
        data.setDirty();
        data.writeDatabase(TerritoryMod.instance().database);
        data.writeClaimsFile();
        player.sendSystemMessage(Component.literal("현재 청크 영토를 삭제했습니다."));
        refreshMarkers(BlueMapAPI.getInstance().orElse(null), server);
        return 1;
    }

    private static int cancelUnclaim(ServerPlayer player) {
        pendingRemovals.remove(player.getUUID());
        player.sendSystemMessage(Component.literal("영토 삭제를 취소했습니다."));
        return 1;
    }

    private static int show(MinecraftServer server, ServerPlayer player) {
        if (!instance().config.isEnabled(player.serverLevel().dimension().location().toString())) {
            player.sendSystemMessage(Component.literal("이 월드에서는 영토를 사용할 수 없습니다."));
            return 0;
        }
        TerritoryData data = TerritoryData.get(server);
        long count = data.claims.values().stream().filter(c -> c.ownerUuid.equals(player.getUUID())).count();
        boolean enabled = toggleBoundaryState(player);
        player.sendSystemMessage(Component.literal("내 영토 청크: " + count + "개\n"
                + (enabled ? "영토 파티클을 켰습니다." : "영토 파티클을 껐습니다.")));
        return 1;
    }

    private static int toggleBoundary(MinecraftServer server, ServerPlayer player) {
        if (!instance().config.isEnabled(player.serverLevel().dimension().location().toString())) {
            player.sendSystemMessage(Component.literal("이 월드에서는 영토를 사용할 수 없습니다."));
            return 0;
        }
        boolean enabled = toggleBoundaryState(player);
        player.sendSystemMessage(Component.literal(enabled
                ? "영토 파티클을 켰습니다."
                : "영토 파티클을 껐습니다."));
        return 1;
    }

    private static boolean toggleBoundaryState(ServerPlayer player) {
        if (boundaryViewers.remove(player.getUUID())) return false;
        boundaryViewers.add(player.getUUID());
        return true;
    }

    private static int list(MinecraftServer server, ServerPlayer player) {
        if (!instance().config.isEnabled(player.serverLevel().dimension().location().toString())) {
            player.sendSystemMessage(Component.literal("이 월드에서는 영토를 사용할 수 없습니다."));
            return 0;
        }
        TerritoryData data = TerritoryData.get(server);
        Map<String, Integer> counts = new HashMap<>();
        data.claims.values().forEach(claim -> counts.merge(claim.territoryName, 1, Integer::sum));
        if (counts.isEmpty()) {
            player.sendSystemMessage(Component.literal("등록된 영토가 없습니다."));
        } else {
            counts.forEach((name, count) ->
                    player.sendSystemMessage(Component.literal(name + ": " + count + "청크")));
        }
        return counts.size();
    }

    private static void showBoundary(ServerPlayer player) {
        if (!boundaryViewers.contains(player.getUUID())) return;
        TerritoryData data = TerritoryData.get(player.server);
        String dimension = player.serverLevel().dimension().location().toString();
        Map<String, List<ChunkPos>> groups = new HashMap<>();
        data.claims.forEach((key, claim) -> {
            if (claim.ownerUuid.equals(player.getUUID()) && worldMatches(dimension, key)) {
                String group = key.substring(0, key.indexOf('|')) + "|" + claim.territoryName;
                groups.computeIfAbsent(group, ignored -> new ArrayList<>()).add(parseChunk(key));
            }
        });
        int y = Math.max(player.blockPosition().getY() + 1, player.serverLevel().getMinBuildHeight() + 1);
        for (List<ChunkPos> chunks : groups.values()) {
            sendOuterBoundary(player, chunks, y);
        }
    }

    private static void sendOuterBoundary(ServerPlayer player, List<ChunkPos> chunks, int y) {
        Map<Long, Boolean> claimed = new HashMap<>();
        for (ChunkPos chunk : chunks) {
            claimed.put(ChunkPos.asLong(chunk.x, chunk.z), true);
        }
        Map<String, List<BoundarySegment>> segments = new HashMap<>();
        for (ChunkPos chunk : chunks) {
            if (!claimed.containsKey(ChunkPos.asLong(chunk.x, chunk.z - 1))) {
                addSegment(segments, "z", chunk.x * 16, (chunk.x + 1) * 16, chunk.z * 16);
            }
            if (!claimed.containsKey(ChunkPos.asLong(chunk.x, chunk.z + 1))) {
                addSegment(segments, "z", chunk.x * 16, (chunk.x + 1) * 16, (chunk.z + 1) * 16);
            }
            if (!claimed.containsKey(ChunkPos.asLong(chunk.x - 1, chunk.z))) {
                addSegment(segments, "x", chunk.z * 16, (chunk.z + 1) * 16, chunk.x * 16);
            }
            if (!claimed.containsKey(ChunkPos.asLong(chunk.x + 1, chunk.z))) {
                addSegment(segments, "x", chunk.z * 16, (chunk.z + 1) * 16, (chunk.x + 1) * 16);
            }
        }
        for (List<BoundarySegment> line : segments.values()) {
            line.sort((first, second) -> Integer.compare(first.start, second.start));
            int index = 0;
            while (index < line.size()) {
                BoundarySegment segment = line.get(index++);
                int end = segment.end;
                while (index < line.size() && line.get(index).start <= end) {
                    end = Math.max(end, line.get(index++).end);
                }
                for (int coordinate = segment.start; coordinate <= end; coordinate++) {
                    double x = segment.axis.equals("x") ? segment.fixed : coordinate;
                    double z = segment.axis.equals("z") ? segment.fixed : coordinate;
                    player.serverLevel().sendParticles(player, BORDER_PARTICLE, true, x, y, z, 1, 0, 0, 0, 0);
                }
            }
        }
    }

    private static void addSegment(Map<String, List<BoundarySegment>> segments, String axis,
                                   int start, int end, int fixed) {
        segments.computeIfAbsent(axis + ":" + fixed, ignored -> new ArrayList<>())
                .add(new BoundarySegment(axis, start, end, fixed));
    }

    private static void refreshMarkers(BlueMapAPI api, MinecraftServer server) {
        if (api == null || server == null) return;
        TerritoryData data = TerritoryData.get(server);
        for (BlueMapMap map : api.getMaps()) {
            MarkerSet set = map.getMarkerSets().computeIfAbsent(MARKER_SET_ID,
                    id -> MarkerSet.builder().label("영토").toggleable(true).defaultHidden(false).sorting(-10).build());
            set.getMarkers().clear();
            String dimension = map.getWorld().getId();
            mergedAreas(data.claims, dimension).forEach(area -> {
                double x0 = area.minChunkX * 16.0, z0 = area.minChunkZ * 16.0;
                ExtrudeMarker marker = ExtrudeMarker.builder()
                        .label(area.territoryName)
                        .detail("영토: " + area.territoryName + "<br>소유자: " + area.ownerName
                                + "<br>크기: " + area.chunkCount + "청크")
                        .shape(Shape.createRect(x0, z0, (area.maxChunkX + 1) * 16.0,
                                (area.maxChunkZ + 1) * 16.0), -64, 320)
                        .lineColor(new Color(0x35A7FF, 0.95f))
                        .fillColor(new Color(0x35A7FF, 0.22f))
                        .lineWidth(3)
                        .depthTestEnabled(false)
                        .listed(false)
                        .build();
                set.put(area.id(), marker);
            });
        }
    }

    private static List<MergedArea> mergedAreas(Map<String, Claim> claims, String dimension) {
        Map<String, List<ChunkPos>> grouped = new HashMap<>();
        Map<String, Claim> representatives = new HashMap<>();
        claims.forEach((key, claim) -> {
            if (!worldMatches(dimension, key)) return;
            String group = key.substring(0, key.indexOf('|')) + "|" + claim.ownerUuid + "|" + claim.territoryName;
            grouped.computeIfAbsent(group, ignored -> new ArrayList<>()).add(parseChunk(key));
            representatives.putIfAbsent(group, claim);
        });

        List<MergedArea> result = new ArrayList<>();
        grouped.forEach((group, chunks) -> {
            Claim claim = representatives.get(group);
            int minX = chunks.stream().mapToInt(pos -> pos.x).min().orElse(0);
            int maxX = chunks.stream().mapToInt(pos -> pos.x).max().orElse(0);
            int minZ = chunks.stream().mapToInt(pos -> pos.z).min().orElse(0);
            int maxZ = chunks.stream().mapToInt(pos -> pos.z).max().orElse(0);
            result.add(new MergedArea(group, claim.ownerUuid, claim.ownerName, claim.territoryName,
                    minX, maxX, minZ, maxZ, chunks.size()));
        });
        return result;
    }

    private static boolean worldMatches(String mapWorldId, String claimKey) {
        int separator = claimKey.indexOf('|');
        if (separator < 0) return false;
        String dimension = claimKey.substring(0, separator);
        return mapWorldId.contains(dimension)
                || mapWorldId.contains(dimension.replace("minecraft:", ""));
    }

    private static String key(ServerLevel level, ChunkPos chunk) {
        return level.dimension().location() + "|" + chunk.x + "," + chunk.z;
    }

    private static ChunkPos parseChunk(String key) {
        String coords = key.substring(key.lastIndexOf('|') + 1);
        String[] split = coords.split(",");
        return new ChunkPos(Integer.parseInt(split[0]), Integer.parseInt(split[1]));
    }

    private static final class Claim {
        private final UUID ownerUuid;
        private final String ownerName;
        private final String territoryName;

        private Claim(UUID ownerUuid, String ownerName, String territoryName) {
            this.ownerUuid = ownerUuid;
            this.ownerName = ownerName;
            this.territoryName = territoryName;
        }

    }

    private record MergedArea(String id, UUID ownerUuid, String ownerName, String territoryName,
                              int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ,
                              int chunkCount) {}

    private record PendingRemoval(String claimKey, long createdAt) {}

    private record BoundarySegment(String axis, int start, int end, int fixed) {}

    private static final class TerritoryData extends SavedData {
        private static final SavedData.Factory<TerritoryData> TYPE =
                new SavedData.Factory<>(TerritoryData::new, TerritoryData::load);
        private final Map<String, Claim> claims = new HashMap<>();

        private static TerritoryData get(MinecraftServer server) {
            return server.overworld().getDataStorage().computeIfAbsent(TYPE, DATA_ID);
        }

        private static TerritoryData load(CompoundTag tag, net.minecraft.core.HolderLookup.Provider ignored) {
            TerritoryData data = new TerritoryData();
            ListTag list = tag.getList("claims", Tag.TAG_COMPOUND);
            for (Tag value : list) {
                CompoundTag claim = (CompoundTag) value;
                data.claims.put(claim.getString("key"), new Claim(
                        claim.getUUID("uuid"), claim.getString("owner"), claim.getString("name")));
            }
            return data;
        }

        public CompoundTag save(CompoundTag tag, net.minecraft.core.HolderLookup.Provider ignored) {
            ListTag list = new ListTag();
            claims.forEach((key, claim) -> {
                CompoundTag value = new CompoundTag();
                value.putString("key", key);
                value.putUUID("uuid", claim.ownerUuid);
                value.putString("owner", claim.ownerName);
                value.putString("name", claim.territoryName);
                list.add(value);
            });
            tag.put("claims", list);
            return tag;
        }

        private void writeClaimsFile() {
            TerritoryMod.thisWriteClaimsFile(this);
        }

        private void loadDatabase(TerritoryDatabase database) {
            List<TerritoryDatabase.Row> rows = database.load();
            if (!rows.isEmpty() || claims.isEmpty()) {
                claims.clear();
                for (TerritoryDatabase.Row row : rows) {
                    claims.put(row.key(), new Claim(row.ownerUuid(), row.ownerName(), row.territoryName()));
                }
            }
        }

        private void writeDatabase(TerritoryDatabase database) {
            database.replace(claims.entrySet().stream()
                    .map(entry -> new TerritoryDatabase.Row(entry.getKey(), entry.getValue().ownerUuid,
                            entry.getValue().ownerName, entry.getValue().territoryName))
                    .toList());
        }

        private boolean isAdjacentToTerritory(ServerLevel level, ChunkPos chunk, UUID ownerUuid, String territoryName) {
            String dimension = level.dimension().location().toString();
            int[][] neighbors = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            boolean hasNamedTerritory = false;
            for (Claim claim : claims.values()) {
                if (claim.ownerUuid.equals(ownerUuid) && claim.territoryName.equals(territoryName)) {
                    hasNamedTerritory = true;
                    break;
                }
            }
            if (!hasNamedTerritory) return true;

            for (int[] neighbor : neighbors) {
                String neighborKey = dimension + "|" + (chunk.x + neighbor[0]) + "," + (chunk.z + neighbor[1]);
                Claim neighborClaim = claims.get(neighborKey);
                if (neighborClaim != null
                        && neighborClaim.ownerUuid.equals(ownerUuid)
                        && neighborClaim.territoryName.equals(territoryName)) {
                    return true;
                }
            }
            return false;
        }

        private String findAdjacentTerritory(ServerLevel level, ChunkPos chunk, UUID ownerUuid) {
            String dimension = level.dimension().location().toString();
            Map<String, Boolean> names = new HashMap<>();
            int[][] neighbors = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (int[] neighbor : neighbors) {
                String neighborKey = dimension + "|" + (chunk.x + neighbor[0]) + "," + (chunk.z + neighbor[1]);
                Claim claim = claims.get(neighborKey);
                if (claim != null && claim.ownerUuid.equals(ownerUuid)) {
                    names.put(claim.territoryName, Boolean.TRUE);
                }
            }
            return names.size() == 1 ? names.keySet().iterator().next() : null;
        }
    }

    private static void thisWriteClaimsFile(TerritoryData data) {
        try {
            java.nio.file.Path file = java.nio.file.Path.of("config", "territory", "claims.txt");
            java.nio.file.Files.createDirectories(file.getParent());
            java.nio.file.Files.write(file, data.claims.keySet().stream().sorted().toList());
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("Failed to write territory claims file", ex);
        }
    }
}
