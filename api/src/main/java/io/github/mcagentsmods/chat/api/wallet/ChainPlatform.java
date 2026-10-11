package io.github.mcagentsmods.chat.api.wallet;

import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * A chain a wallet holds an address on.
 *
 * <p>One wallet is not one address. MetaMask derives a separate address per chain family
 * from the same secret, so the wallet a player calls "Main" has an Ethereum address, a
 * Bitcoin address, a Solana address, and a Tron address at the same time — and every EVM
 * chain in the list shares the Ethereum one. That is why the store keys an address by
 * wallet <em>and</em> platform rather than keeping a single column on the wallet.
 *
 * <p>The order below is the order the interface lists them in, which is MetaMask's own.
 *
 * <h2>This list will go out of date, and that is allowed</h2>
 *
 * <p>New chains appear faster than this mod ships, so {@link #OTHER} exists to hold an
 * address for something not named here rather than refusing to store it. Nothing in the
 * mod treats an unknown chain as an error.
 */
public enum ChainPlatform {

    /** Ethereum mainnet. Every other EVM entry below shares its address format. */
    ETHEREUM("Ethereum", 1L),
    BITCOIN("Bitcoin", 0L),
    SOLANA("Solana", 0L),
    LINEA("Linea", 59144L),
    BASE("Base", 8453L),
    BNB_CHAIN("BNB Chain", 56L),
    POLYGON("Polygon", 137L),
    MONAD("Monad", 143L),
    OPTIMISM("OP", 10L),
    ARBITRUM("Arbitrum", 42161L),
    TRON("Tron", 0L),
    /** Anything this list does not name. Stored, listed, and never validated by format. */
    OTHER("Other", 0L);

    private final String displayName;
    private final long evmChainId;

    ChainPlatform(String displayName, long evmChainId) {
        this.displayName = displayName;
        this.evmChainId = evmChainId;
    }

    /** What the player sees in the interface. */
    public String displayName() {
        return displayName;
    }

    /**
     * Whether this chain uses Ethereum's address format and signing scheme.
     *
     * <p>This is the line that decides what the mod can prove. An EVM address can be
     * verified from a signature the player produces in MetaMask; a Bitcoin, Solana, or Tron
     * address is taken on the player's word, because the browser extension does not offer
     * the mod a way to sign with it.
     */
    public boolean isEvm() {
        return evmChainId > 0;
    }

    /** The EIP-155 chain id, or empty when this is not an EVM chain. */
    public OptionalLong evmChainId() {
        return isEvm() ? OptionalLong.of(evmChainId) : OptionalLong.empty();
    }

    /** How this platform is written in the database and on the wire. */
    public String storageName() {
        return name();
    }

    /**
     * The EVM chain the browser was connected to, if it is one this list names.
     *
     * <p>A chain id the mod does not recognise is not a failure — the caller falls back to
     * {@link #ETHEREUM}, which holds the same address on every EVM chain anyway.
     */
    public static Optional<ChainPlatform> fromEvmChainId(long chainId) {
        for (ChainPlatform platform : values()) {
            if (platform.evmChainId == chainId && platform.isEvm()) {
                return Optional.of(platform);
            }
        }
        return Optional.empty();
    }

    /**
     * Reads a platform back from storage.
     *
     * <p>An unrecognised value becomes {@link #OTHER} rather than throwing. A row written
     * by a newer version of the mod must not make an older one unable to read the player's
     * wallets.
     */
    public static ChainPlatform fromStorageName(String value) {
        if (value == null || value.isBlank()) {
            return OTHER;
        }
        String normalised = value.trim().toUpperCase(Locale.ROOT);
        for (ChainPlatform platform : values()) {
            if (platform.name().equals(normalised)) {
                return platform;
            }
        }
        return OTHER;
    }
}
