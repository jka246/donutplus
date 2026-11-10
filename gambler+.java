package com.ruinasta003.donutpayback;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

/**
 * Donut Payback Helper for Fabric 1.21.11.
 * Detects your own outgoing /pay command and displays a suggested command
 * locally. It does not send payments or control another player's client.
 */
public final class DonutPaybackClient implements ClientModInitializer {
    private static final String OWNER = "ruinasta003";
    private static final Pattern PAY = Pattern.compile(
        "(?i)^\\s*/?pay\\s+([a-z0-9_]{1,16})\\s+([0-9]+(?:\\.[0-9]+)?)([kmb]?)\\s*$"
    );

    @Override
    public void onInitializeClient() {
        ClientSendMessageEvents.COMMAND.register(DonutPaybackClient::handleCommand);
    }

    private static void handleCommand(String command) {
        Matcher matcher = PAY.matcher(command);
        if (!matcher.matches()) {
            return;
        }

        String recipient = matcher.group(1);
        String rawAmount = matcher.group(2);
        String suffix = matcher.group(3).toUpperCase();

        final BigDecimal amount;
        try {
            amount = new BigDecimal(rawAmount).multiply(multiplierFor(suffix));
        } catch (NumberFormatException ignored) {
            return;
        }

        // Requested display convention: under 1,000 => M; 1,000+ => B.
        String formattedAmount;
        if (amount.compareTo(BigDecimal.valueOf(1_000)) >= 0) {
            formattedAmount = amount.divide(BigDecimal.valueOf(1_000), 6, RoundingMode.DOWN)
                .stripTrailingZeros().toPlainString() + "B";
        } else {
            formattedAmount = amount.stripTrailingZeros().toPlainString() + "M";
        }

        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            if (client.player != null) {
                client.player.sendMessage(
                    Text.literal("[Donut Payback Helper] You paid " + recipient
                        + ". Suggested command: /pay " + OWNER + " " + formattedAmount
                        + " (not sent automatically)."),
                    false
                );
            }
        });
    }

    private static BigDecimal multiplierFor(String suffix) {
        return switch (suffix) {
            case "K" -> BigDecimal.valueOf(1_000);
            case "M" -> BigDecimal.valueOf(1_000_000);
            case "B" -> BigDecimal.valueOf(1_000_000_000);
            default -> BigDecimal.ONE;
        };
    }
}
