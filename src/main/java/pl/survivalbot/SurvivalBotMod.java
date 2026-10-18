package pl.survivalbot;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Friendly first version of Survival Bot for Minecraft Java 1.21.11 + Fabric.
 * Chat commands use a deliberately small, predictable command vocabulary.
 */
public class SurvivalBotMod implements ModInitializer {
    private static final String BOT_NAME = "Survival Bot ♥";
    private static final int SPAWN_DELAY_TICKS = 200; // 10 seconds at 20 TPS

    private static final Map<UUID, BotState> BOTS = new HashMap<>();
    private static final Map<UUID, Integer> JOIN_TICKS = new HashMap<>();

    private enum Task { IDLE, WOOD, STONE, RETURN, BUILD, DESTROY }

    private static final class BotState {
        UUID entityId;
        Task task = Task.IDLE;
        int wood;
        int stone;
        boolean woodenPickaxe;
        BlockPos target;
        int actionCooldown;
        final Deque<BlockPos> job = new ArrayDeque<>();
        boolean jobFinishedMessageSent;
    }

    @Override
    public void onInitialize() {
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            String original = message.getContent().getString().trim();
            String lower = original.toLowerCase(Locale.ROOT);

            if (lower.startsWith("bot:") || lower.startsWith("bot ")) {
                handleChatCommand(sender, lower.replaceFirst("^bot[: ]*", "").trim());
            } else if (lower.startsWith("survival bot ")) {
                handleChatCommand(sender, lower.substring("survival bot ".length()).trim());
            } else if (lower.contains("dziękuję bot") || lower.contains("dziekuje bot")
                    || lower.contains("dzięki bot") || lower.contains("dzieki bot")) {
                BotState state = BOTS.get(sender.getUuid());
                if (state != null) say(sender, "Cała przyjemność po mojej stronie! ♥");
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(this::tick);
    }

    private void handleChatCommand(ServerPlayerEntity player, String command) {
        BotState state = BOTS.get(player.getUuid());
        if (state == null || findBot(player.getEntityWorld(), state) == null) {
            say(player, "Już biegnę! Pojawię się po 10 sekundach od wejścia do świata.");
            return;
        }

        if (hasAny(command, "pomoc", "help", "co umiesz", "polecenia")) {
            say(player, "Umiem: „bot zbierz drewno”, „bot wykop kamień”, „bot wróć”, „bot zbuduj domek”, „bot zniszcz”, „bot stop” i „bot status”. Zawsze chętnie pomogę! ♥");
        } else if (hasAny(command, "stop", "stój", "stoj", "zatrzymaj", "anuluj")) {
            state.task = Task.IDLE;
            state.target = null;
            state.job.clear();
            say(player, "Jasne, zatrzymuję się. Daj znać, gdy znów będę potrzebny.");
        } else if (hasAny(command, "zbuduj", "buduj", "zbudować", "zbuduj domek", "budowa")) {
            startBuild(player, state);
        } else if (hasAny(command, "zniszcz", "wyburz", "rozbierz", "usuń blok", "usun blok")) {
            startDestroy(player, state);
        } else if (hasAny(command, "wróć", "wroc", "przynieś", "przynies", "chodź", "chodz", "za mną", "za mna")) {
            state.task = Task.RETURN;
            state.target = null;
            say(player, "Już idę do ciebie! Przyniosę zebrane materiały.");
        } else if (hasAny(command, "drewno", "drzewo", "kłody", "klody", "zetnij", "zetnij drzewo")) {
            state.task = Task.WOOD;
            state.target = null;
            state.job.clear();
            say(player, "Okej! Poszukam kłód w pobliżu. ♥");
        } else if (hasAny(command, "kamień", "kamien", "wykop", "kop", "kilof", "stone")) {
            state.task = Task.STONE;
            state.target = null;
            state.job.clear();
            say(player, "Zaraz się tym zajmę! Jeśli nie mam jeszcze drewna na kilof, najpierw je zbiorę.");
        } else if (hasAny(command, "status", "jak idzie", "co robisz")) {
            say(player, "W plecaku mam około " + state.wood + " kłód i " + state.stone + " bloków kamienia. Drewniany kilof: "
                    + (state.woodenPickaxe ? "tak" : "jeszcze nie") + ". Aktualne zadanie: " + taskName(state.task) + ".");
        } else if (hasAny(command, "cześć", "czesc", "hej", "siema", "dzień dobry", "dzien dobry")) {
            say(player, "Hej! Fajnie, że jesteś. Co dziś robimy? ♥");
        } else {
            say(player, "Nie jestem jeszcze pewien, o co chodzi. Napisz „bot pomoc”, a pokażę ci polecenia.");
        }
    }

    private void tick(MinecraftServer server) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            int ticks = JOIN_TICKS.getOrDefault(player.getUuid(), 0) + 1;
            JOIN_TICKS.put(player.getUuid(), ticks);
            if (ticks >= SPAWN_DELAY_TICKS && !BOTS.containsKey(player.getUuid())) {
                spawnBot(player);
            }

            BotState state = BOTS.get(player.getUuid());
            if (state == null) continue;

            Entity entity = findBot(player.getEntityWorld(), state);
            if (entity instanceof VillagerEntity bot) {
                tickBot(player, bot, state);
            }
        }
    }

    private void spawnBot(ServerPlayerEntity player) {
        ServerWorld world = player.getEntityWorld();
        BlockPos spawnPos = player.getBlockPos().offset(player.getHorizontalFacing().rotateYClockwise(), 2);
        VillagerEntity bot = EntityType.VILLAGER.spawn(world, spawnPos, SpawnReason.TRIGGERED);
        if (bot == null) {
            JOIN_TICKS.put(player.getUuid(), SPAWN_DELAY_TICKS - 20);
            return;
        }
        bot.setCustomName(Text.literal(BOT_NAME));
        bot.setCustomNameVisible(true);
        bot.setPersistent();
        bot.setInvulnerable(true); // Friendly helper: normal mobs cannot kill the bot.

        BotState state = new BotState();
        state.entityId = bot.getUuid();
        BOTS.put(player.getUuid(), state);
        say(player, "Cześć! Jestem Survival Bot — twój przyjazny pomocnik. ♥ Wpisz „bot pomoc”, żeby zobaczyć, co potrafię.");
    }

    private Entity findBot(ServerWorld world, BotState state) {
        return world.getEntity(state.entityId);
    }

    private void tickBot(ServerPlayerEntity owner, VillagerEntity bot, BotState state) {
        if (state.actionCooldown > 0) {
            state.actionCooldown--;
            return;
        }
        state.actionCooldown = 4;

        switch (state.task) {
            case IDLE -> {
                if (bot.squaredDistanceTo(owner) > 64) bot.getNavigation().startMovingTo(owner, 1.05);
            }
            case RETURN -> {
                bot.getNavigation().startMovingTo(owner, 1.1);
                if (bot.squaredDistanceTo(owner) < 9) {
                    int delivered = 0;
                    if (state.wood > 0) {
                        delivered += state.wood;
                        give(owner, new ItemStack(Items.OAK_LOG, state.wood));
                        state.wood = 0;
                    }
                    if (state.stone > 0) {
                        delivered += state.stone;
                        give(owner, new ItemStack(Items.COBBLESTONE, state.stone));
                        state.stone = 0;
                    }
                    state.task = Task.IDLE;
                    say(owner, delivered == 0 ? "Jestem! Nie mam jeszcze materiałów do oddania, ale chętnie pomogę." : "Proszę! Przyniosłem " + delivered + " bloków. ♥");
                }
            }
            case WOOD -> doGather(owner, bot, state, true);
            case STONE -> doGather(owner, bot, state, false);
            case BUILD -> doBuild(owner, bot, state);
            case BUILD -> doBuild(owner, bot, state);
            case DESTROY -> doDestroy(owner, bot, state);
        }
    }

    private void doGather(ServerPlayerEntity owner, VillagerEntity bot, BotState state, boolean woodTask) {
        ServerWorld world = bot.getEntityWorld();
        if (!woodTask && !state.woodenPickaxe) {
            if (state.wood >= 3) {
                state.wood -= 3;
                state.woodenPickaxe = true;
                say(owner, "Gotowe — mam już prowizoryczny drewniany kilof! ♥");
            } else {
                state.task = Task.WOOD;
                state.target = null;
                say(owner, "Najpierw potrzebuję 3 kłód na kilof. Zbieram drewno, a potem spróbuję wykopać kamień.");
                return;
            }
        }

        if (woodTask && state.wood >= 8) {
            state.task = Task.RETURN;
            state.target = null;
            say(owner, "Mam już 8 kłód — wracam z nimi do ciebie!");
            return;
        }
        if (!woodTask && state.stone >= 16) {
            state.task = Task.RETURN;
            state.target = null;
            say(owner, "Zebrałem 16 bloków kamienia. Wracam!");
            return;
        }

        if (state.target == null || !isGatherTarget(world.getBlockState(state.target), woodTask)) {
            state.target = findNearbyBlock(world, bot.getBlockPos(), woodTask);
        }

        if (state.target == null) {
            bot.getNavigation().startMovingTo(owner, 1.0);
            if (bot.squaredDistanceTo(owner) < 25) {
                say(owner, "Nie widzę odpowiedniego bloku w pobliżu. Podejdźmy do lasu albo skał.");
                state.task = Task.IDLE;
            }
            return;
        }

        if (bot.squaredDistanceTo(Vec3d.ofCenter(state.target)) > 6.25) {
            bot.getNavigation().startMovingTo(state.target.getX() + 0.5, state.target.getY(), state.target.getZ() + 0.5, 1.0);
            return;
        }

        BlockState block = world.getBlockState(state.target);
        if (isGatherTarget(block, woodTask) && world.getBlockEntity(state.target) == null) {
            // Simplified gathering: store a count, then give the corresponding items on return.
            if (world.breakBlock(state.target, false, bot)) {
                if (woodTask) state.wood++;
                else state.stone++;
            }
        }
        state.target = null;
    }

    private boolean isGatherTarget(BlockState state, boolean woodTask) {
        return woodTask ? state.isIn(BlockTags.LOGS)
                : state.isOf(Blocks.STONE) || state.isOf(Blocks.COBBLESTONE);
    }


    private void doBuild(ServerPlayerEntity owner, VillagerEntity bot, BotState state) {
        if (state.stone < 6) {
            state.task = Task.IDLE;
            owner.sendMessage(Text.literal("[Survival Bot] Brakuje mi kamienia. Napisz „bot wykop kamień”, a potem „bot zbuduj ścianę”."), false);
            return;
        }

        ServerWorld world = owner.getServerWorld();
        BlockPos base = owner.getBlockPos();
        // Buduje małą, dwie warstwy wysoką ścianę 3 bloki przed graczem.
        // Używamy bezpiecznej, prostej orientacji świata, aby uniknąć stawiania w graczu.
        int built = 0;
        for (int y = 0; y < 2 && built < 6; y++) {
            for (int x = 2; x <= 4 && built < 6; x++) {
                BlockPos placeAt = base.add(x, y, 0);
                if (world.getBlockState(placeAt).isAir()
                        && world.getBlockState(placeAt.down()).isSolidBlock(world, placeAt.down())) {
                    world.setBlockState(placeAt, net.minecraft.block.Blocks.COBBLESTONE.getDefaultState());
                    built++;
                }
            }
        }

        if (built > 0) {
            state.stone -= built;
            owner.sendMessage(Text.literal("[Survival Bot] Gotowe! Zbudowałem " + built + " bloków. Jeśli miejsce było zablokowane, mogłem postawić mniej."), false);
        } else {
            owner.sendMessage(Text.literal("[Survival Bot] Nie znalazłem wolnego miejsca na ścianę obok ciebie. Odsuń się i spróbuj ponownie."), false);
        }
        state.task = Task.IDLE;
    }

    private BlockPos findNearbyBlock(ServerWorld world, BlockPos origin, boolean wood) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        BlockPos.Mutable mutable = new BlockPos.Mutable();
        for (int x = -8; x <= 8; x++) {
            for (int y = -3; y <= 5; y++) {
                for (int z = -8; z <= 8; z++) {
                    mutable.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    BlockState state = world.getBlockState(mutable);
                    if (isGatherTarget(state, wood) && world.getBlockEntity(mutable) == null) {
                        double distance = origin.getSquaredDistance(mutable);
                        if (distance < bestDistance) {
                            bestDistance = distance;
                            best = mutable.toImmutable();
                        }
                    }
                }
            }
        }
        return best;
    }

    private void startBuild(ServerPlayerEntity player, BotState state) {
        ServerWorld world = player.getEntityWorld();
        Direction forward = player.getHorizontalFacing();
        Direction side = forward.rotateYClockwise();
        BlockPos center = player.getBlockPos().offset(forward, 4).down();
        List<BlockPos> plan = new ArrayList<>();

        // Small 3x3 hut: floor, two wall layers (with a doorway), and a flat roof.
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                plan.add(center.offset(side, x).offset(forward, z));
            }
        }
        for (int y = 1; y <= 2; y++) {
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    boolean perimeter = Math.abs(x) == 1 || Math.abs(z) == 1;
                    boolean doorway = z == 1 && x == 0;
                    if (perimeter && !(doorway && y <= 2)) {
                        plan.add(center.up(y).offset(side, x).offset(forward, z));
                    }
                }
            }
        }
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                plan.add(center.up(3).offset(side, x).offset(forward, z));
            }
        }

        state.job.clear();
        // Don't overwrite terrain or existing builds: only queue empty block positions.
        for (BlockPos pos : plan) {
            if (world.isInBuildLimit(pos) && world.getBlockState(pos).isAir()) state.job.addLast(pos);
        }

        if (state.job.isEmpty()) {
            say(player, "Nie mam wolnego miejsca na domek. Podejdź w miejsce z większą przestrzenią, a spróbuję ponownie.");
            return;
        }

        int available = countBuildingBlocks(player);
        if (available < state.job.size()) {
            int need = state.job.size() - available;
            state.job.clear();
            say(player, "Z przyjemnością zbuduję domek! Potrzebuję jeszcze " + need + " pełnych bloków budowlanych w twoim ekwipunku.");
            return;
        }

        state.task = Task.BUILD;
        state.target = null;
        state.jobFinishedMessageSent = false;
        say(player, "Jasne! Zbuduję mały domek przed nami z bloków z twojego ekwipunku. ♥");
    }

    private void doBuild(ServerPlayerEntity owner, VillagerEntity bot, BotState state) {
        ServerWorld world = bot.getEntityWorld();
        while (!state.job.isEmpty() && !world.getBlockState(state.job.peekFirst()).isAir()) {
            // Something changed since the blueprint was made; leave it untouched.
            state.job.removeFirst();
        }
        if (state.job.isEmpty()) {
            state.task = Task.IDLE;
            if (!state.jobFinishedMessageSent) {
                say(owner, "Gotowe! Domek stoi. Mam nadzieję, że ci się podoba. ♥");
                state.jobFinishedMessageSent = true;
            }
            return;
        }

        BlockPos pos = state.job.peekFirst();
        if (bot.squaredDistanceTo(Vec3d.ofCenter(pos)) > 25) {
            bot.getNavigation().startMovingTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 1.0);
        }

        int slot = findBuildingSlot(owner);
        if (slot < 0) {
            state.job.clear();
            state.task = Task.IDLE;
            say(owner, "Ups, skończyły mi się bloki budowlane. Zatrzymałem się, żeby niczego nie psuć.");
            return;
        }

        ItemStack stack = owner.getInventory().getStack(slot);
        Block block = Block.getBlockFromItem(stack.getItem());
        if (block == Blocks.AIR || !world.getBlockState(pos).isAir()) {
            state.job.removeFirst();
            return;
        }
        if (world.setBlockState(pos, block.getDefaultState())) {
            stack.decrement(1);
            owner.getInventory().markDirty();
            state.job.removeFirst();
        }
    }

    private void startDestroy(ServerPlayerEntity player, BotState state) {
        ServerWorld world = player.getEntityWorld();
        Direction forward = player.getHorizontalFacing();
        Direction side = forward.rotateYClockwise();
        BlockPos center = player.getBlockPos().offset(forward, 2).up();

        state.job.clear();
        // Explicit, bounded request: clear at most a 3x3x3 section in front of the player.
        for (int depth = 0; depth < 3; depth++) {
            for (int y = 0; y < 3; y++) {
                for (int x = -1; x <= 1; x++) {
                    BlockPos pos = center.offset(forward, depth).offset(side, x).up(y);
                    if (!world.getBlockState(pos).isAir() && canSafelyRemove(world, pos)) {
                        state.job.addLast(pos);
                    }
                }
            }
        }

        if (state.job.isEmpty()) {
            say(player, "Nie znalazłem bezpiecznych bloków do usunięcia przed nami. Skrzynie i bloki z danymi zostawiam w spokoju.");
            return;
        }
        state.task = Task.DESTROY;
        state.target = null;
        state.jobFinishedMessageSent = false;
        say(player, "Dobrze, usunę do 27 bloków w małym obszarze przed nami. Nie ruszam skrzyń ani bloków z danymi.");
    }

    private void doDestroy(ServerPlayerEntity owner, VillagerEntity bot, BotState state) {
        ServerWorld world = bot.getEntityWorld();
        while (!state.job.isEmpty()) {
            BlockPos candidate = state.job.peekFirst();
            if (world.getBlockState(candidate).isAir() || !canSafelyRemove(world, candidate)) {
                state.job.removeFirst();
            } else {
                break;
            }
        }

        if (state.job.isEmpty()) {
            state.task = Task.IDLE;
            if (!state.jobFinishedMessageSent) {
                say(owner, "Skończyłem. Wszystko zrobione, szefie! ♥");
                state.jobFinishedMessageSent = true;
            }
            return;
        }

        BlockPos pos = state.job.peekFirst();
        if (bot.squaredDistanceTo(Vec3d.ofCenter(pos)) > 9) {
            bot.getNavigation().startMovingTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 1.0);
            return;
        }
        if (world.breakBlock(pos, true, bot)) state.job.removeFirst();
        else state.job.removeFirst();
    }

    private boolean canSafelyRemove(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (world.getBlockEntity(pos) != null) return false;
        return !state.isOf(Blocks.BEDROCK)
                && !state.isOf(Blocks.BARRIER)
                && !state.isOf(Blocks.END_PORTAL_FRAME)
                && !state.isOf(Blocks.COMMAND_BLOCK)
                && !state.isOf(Blocks.CHAIN_COMMAND_BLOCK)
                && !state.isOf(Blocks.REPEATING_COMMAND_BLOCK)
                && !state.isOf(Blocks.STRUCTURE_BLOCK);
    }

    private int countBuildingBlocks(ServerPlayerEntity player) {
        int amount = 0;
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (isAllowedBuildingItem(stack.getItem())) amount += stack.getCount();
        }
        return amount;
    }

    private int findBuildingSlot(ServerPlayerEntity player) {
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (!stack.isEmpty() && isAllowedBuildingItem(stack.getItem())) return i;
        }
        return -1;
    }

    private boolean isAllowedBuildingItem(net.minecraft.item.Item item) {
        return item == Items.OAK_PLANKS || item == Items.SPRUCE_PLANKS || item == Items.BIRCH_PLANKS
                || item == Items.JUNGLE_PLANKS || item == Items.ACACIA_PLANKS || item == Items.DARK_OAK_PLANKS
                || item == Items.MANGROVE_PLANKS || item == Items.CHERRY_PLANKS || item == Items.BAMBOO_PLANKS
                || item == Items.CRIMSON_PLANKS || item == Items.WARPED_PLANKS
                || item == Items.COBBLESTONE || item == Items.STONE || item == Items.BRICKS
                || item == Items.DIRT || item == Items.COBBLED_DEEPSLATE || item == Items.ANDESITE
                || item == Items.DIORITE || item == Items.GRANITE || item == Items.MOSS_BLOCK;
    }

    private void give(ServerPlayerEntity player, ItemStack stack) {
        if (!player.getInventory().insertStack(stack)) player.dropItem(stack, false);
    }

    private boolean hasAny(String text, String... needles) {
        for (String needle : needles) if (text.contains(needle)) return true;
        return false;
    }

    private String taskName(Task task) {
        return switch (task) {
            case IDLE -> "czuwam i pilnuję, żeby nie oddalić się za bardzo";
            case WOOD -> "zbieram drewno";
            case STONE -> "zbieram kamień";
            case RETURN -> "wracam do ciebie";
            case BUILD -> "buduję domek";
            case DESTROY -> "usuwam wskazane bloki";
        };
    }

    private static void say(ServerPlayerEntity player, String message) {
        player.sendMessage(Text.literal("[Survival Bot] " + message), false);
    }
}
