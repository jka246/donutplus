package donutgamble.client;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/** Client-side DonutSMP payment ledger. It never sends a payment automatically. */
public class DonutgambleClient implements ClientModInitializer {
    private static final Map<String, BigDecimal> OWED = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private static final Map<String, BigDecimal> PENDING = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private static Path ledgerFile;

    @Override
    public void onInitializeClient() {
        ledgerFile = Minecraft.getInstance().gameDirectory.toPath().resolve("config/donutpayback-ledger.txt");
        loadLedger();

        // Capture /pay player amount. The entry is pending until the player confirms
        // the server accepted the payment with /payback confirm <player>.
        ClientSendMessageEvents.COMMAND.register(command -> {
            String trimmed = command.trim();
            if (!trimmed.regionMatches(true, 0, "pay ", 0, 4)) return;
            String[] parts = trimmed.split("\\s+");
            if (parts.length < 3) return;
            String player = cleanPlayer(parts[1]);
            BigDecimal amount = parseAmount(parts[2]);
            if (player == null || amount == null || amount.signum() <= 0) return;
            PENDING.merge(player, amount, BigDecimal::add);
            saveLedger();
            tell("Payment of " + format(amount) + " to " + player + " recorded as PENDING. After DonutSMP confirms it, run /payback confirm " + player + ".");
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
            literal("payback")
                .then(literal("list").executes(ctx -> { showLedger(); return 1; }))
                .then(literal("confirm").then(argument("player", StringArgumentType.word()).executes(ctx -> {
                    confirm(StringArgumentType.getString(ctx, "player")); return 1;
                })))
                .then(literal("request").then(argument("player", StringArgumentType.word()).executes(ctx -> {
                    request(StringArgumentType.getString(ctx, "player")); return 1;
                })))
                .then(literal("forgive").then(argument("player", StringArgumentType.word()).executes(ctx -> {
                    forgive(StringArgumentType.getString(ctx, "player")); return 1;
                })))
                .then(literal("help").executes(ctx -> {
                    tell("Commands: /payback list | confirm <player> | request <player> | forgive <player>"); return 1;
                }))
        ));
        tell("DonutPayback loaded. Type /payback help. Payments are tracked locally; repayment is not automatic.");
    }

    private static void confirm(String rawPlayer) {
        String player = cleanPlayer(rawPlayer);
        if (player == null) { tell("Invalid player name."); return; }
        BigDecimal amount = PENDING.remove(player);
        if (amount == null) { tell("No pending payment found for " + player + "."); return; }
        OWED.merge(player, amount, BigDecimal::add);
        saveLedger();
        tell("Confirmed " + format(amount) + " paid to " + player + ". Total owed: " + format(OWED.get(player)) + ".");
    }

    private static void request(String rawPlayer) {
        String player = cleanPlayer(rawPlayer);
        BigDecimal amount = player == null ? null : OWED.get(player);
        if (amount == null || amount.signum() <= 0) { tell("No confirmed amount owed by that player."); return; }
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) { tell("Join a server first."); return; }
        String msg = "You owe me " + format(amount) + " from our payment. Please repay when you can.";
        mc.getConnection().sendCommand("msg " + player + " " + msg);
        tell("Sent a repayment reminder to " + player + ". This is only a message, not a forced withdrawal.");
    }

    private static void forgive(String rawPlayer) {
        String player = cleanPlayer(rawPlayer);
        if (player == null || OWED.remove(player) == null) { tell("No confirmed debt found for that player."); return; }
        saveLedger();
        tell("Removed the recorded debt for " + player + ".");
    }

    private static void showLedger() {
        tell("--- DonutPayback ledger ---");
        if (PENDING.isEmpty() && OWED.isEmpty()) tell("No payments recorded yet.");
        PENDING.forEach((p, a) -> tell("PENDING: " + p + " - " + format(a) + " (confirm after server success)"));
        OWED.forEach((p, a) -> tell("OWED: " + p + " - " + format(a)));
        tell("--------------------------");
    }

    private static BigDecimal parseAmount(String input) {
        try {
            String value = input.toLowerCase(Locale.ROOT).replace(",", "").replace("$", "");
            BigDecimal multiplier = BigDecimal.ONE;
            if (value.endsWith("k")) { multiplier = new BigDecimal("1000"); value = value.substring(0, value.length() - 1); }
            else if (value.endsWith("m")) { multiplier = new BigDecimal("1000000"); value = value.substring(0, value.length() - 1); }
            else if (value.endsWith("b")) { multiplier = new BigDecimal("1000000000"); value = value.substring(0, value.length() - 1); }
            BigDecimal amount = new BigDecimal(value).multiply(multiplier);
            if (amount.scale() > 2) return null;
            return amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (Exception ignored) { return null; }
    }

    private static String format(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString();
    }

    private static String cleanPlayer(String player) {
        if (player == null || !player.matches("[A-Za-z0-9_]{1,16}")) return null;
        return player;
    }

    private static void tell(String message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(Component.literal("[Payback] " + message), false);
    }

    private static void loadLedger() {
        try {
            Files.createDirectories(ledgerFile.getParent());
            if (!Files.exists(ledgerFile)) return;
            for (String line : Files.readAllLines(ledgerFile, StandardCharsets.UTF_8)) {
                String[] p = line.split("\\t");
                if (p.length != 3) continue;
                String player = cleanPlayer(p[1]);
                BigDecimal amount = new BigDecimal(p[2]);
                if (player == null || amount.signum() <= 0) continue;
                (p[0].equals("P") ? PENDING : p[0].equals("O") ? OWED : new LinkedHashMap<String, BigDecimal>()).merge(player, amount, BigDecimal::add);
            }
        } catch (Exception e) {
            System.err.println("[DonutPayback] Could not load ledger: " + e.getMessage());
        }
    }

    private static void saveLedger() {
        try {
            Files.createDirectories(ledgerFile.getParent());
            StringBuilder data = new StringBuilder();
            PENDING.forEach((p, a) -> data.append("P\t").append(p).append('\t').append(a.toPlainString()).append('\n'));
            OWED.forEach((p, a) -> data.append("O\t").append(p).append('\t').append(a.toPlainString()).append('\n'));
            Files.writeString(ledgerFile, data, StandardCharsets.UTF_8);
        } catch (IOException e) {
            tell("Could not save ledger: " + e.getMessage());
        }
    }
}
