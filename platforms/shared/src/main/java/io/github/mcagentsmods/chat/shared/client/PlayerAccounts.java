package io.github.mcagentsmods.chat.shared.client;

import io.github.mcagentsmods.chat.api.wallet.Wallet;
import net.minecraft.client.Minecraft;

/**
 * Which account the wallet screen opens on.
 *
 * <p>The signed-in profile's name, because it is the one identity the player already has
 * and did not have to invent. It is only a default: the account is a free-text label, so a
 * player who wants a second set of wallets types a second name and gets one, and a player
 * who shares a computer keeps their wallets apart by keeping their profiles apart.
 *
 * <p>Resolved here rather than in {@code common/}, because this is the only module that may
 * see Minecraft types at all.
 */
public final class PlayerAccounts {

    private PlayerAccounts() {
        throw new AssertionError("No instances.");
    }

    /**
     * The account the player's wallets sit under by default.
     *
     * <p>Falls back to {@link Wallet#DEFAULT_ACCOUNT} rather than failing: a screen that
     * refuses to open because a profile could not be read would be a worse answer than an
     * account called "Personal".
     */
    public static String current(Minecraft minecraft) {
        if (minecraft != null && minecraft.getUser() != null) {
            String name = minecraft.getUser().getName();
            if (name != null && !name.isBlank()) {
                return name.trim();
            }
        }
        return Wallet.DEFAULT_ACCOUNT;
    }
}
