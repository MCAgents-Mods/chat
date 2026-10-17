package io.github.mcagentsmods.chat.shared.client;

import io.github.mcagentsmods.chat.api.wallet.ChainPlatform;
import io.github.mcagentsmods.chat.api.wallet.Wallet;
import io.github.mcagentsmods.chat.api.wallet.WalletBinding;
import io.github.mcagentsmods.chat.common.wallet.AddressFormats;
import io.github.mcagentsmods.chat.common.wallet.LocalWalletBridge;
import io.github.mcagentsmods.chat.common.wallet.SystemBrowser;
import io.github.mcagentsmods.core.api.hologram.Form;
import io.github.mcagentsmods.core.api.hologram.FormButton;
import io.github.mcagentsmods.core.api.hologram.FormField;
import io.github.mcagentsmods.core.api.hologram.FormText;
import io.github.mcagentsmods.core.api.hologram.FormTone;
import io.github.mcagentsmods.core.api.hologram.Hologram;
import io.github.mcagentsmods.core.client.hologram.Holograms;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The two wallet questions that are more than a name or a yes: adding an address by hand, and
 * proving addresses through MetaMask. Both stand as {@link HologramDialogs#ID}, so they share
 * the one dialog place beside the wallets page.
 *
 * <p>Call every method on the game's thread.
 */
final class WalletDialogs {

    private static final Logger LOGGER = LoggerFactory.getLogger(WalletDialogs.class);

    private static final String PREFIX = "gui.mcagents.wallets.";
    private static final String CANCEL = "gui.cancel";

    /** Long enough for any public address; nothing longer is one. */
    private static final int ADDRESS_LENGTH = 255;

    private WalletDialogs() {
        throw new AssertionError("No instances.");
    }

    /**
     * Asks for one chain address to add to {@code wallet}.
     *
     * <p>MetaMask can prove an EVM address, because the browser can sign with it; it cannot do
     * the same for the Bitcoin, Solana and Tron addresses it shows, so those are typed in and
     * recorded as unproved. What this refuses is a private key or a recovery phrase: nothing in
     * the mod needs one, so an input that looks like one is turned away with the reason, said
     * as it is typed.
     *
     * @param onAdd called with the chain and the address in the chain's own form
     */
    static void addAddress(Wallet wallet, BiConsumer<ChainPlatform, String> onAdd) {
        String[] chain = {ChainPlatform.ETHEREUM.displayName()};
        String[] draft = {""};
        Holograms.open(Hologram.of(HologramDialogs.ID, new Form(PREFIX + "address.add", List.of(
                FormText.of(I18n.get(PREFIX + "address.into", wallet.label()), FormTone.SOFT, 1),
                FormField.of(PREFIX + "address.chain", () -> chain[0], text -> chain[0] = text)
                        .withChoices(WalletDialogs::chainNames),
                FormField.of(PREFIX + "address.label", () -> draft[0], text -> draft[0] = text)
                        .withHint(PREFIX + "address.hint")
                        .withMaxLength(ADDRESS_LENGTH),
                new FormText(() -> addressNotice(chain[0], draft[0]),
                        () -> addressTone(chain[0], draft[0]), 3)),
                List.of(new FormButton(PREFIX + "address.save",
                                () -> problem(chain[0], draft[0]).isEmpty(), () -> {
                            ChainPlatform platform = platformOf(chain[0]);
                            String value = draft[0].trim();
                            if (AddressFormats.problem(platform, value).isPresent()) {
                                return;
                            }
                            Holograms.close(HologramDialogs.ID);
                            // Stored the way the chain writes it: EVM addresses in checksum
                            // form, so the mod and MetaMask show the same casing.
                            onAdd.accept(platform, AddressFormats.normalise(platform, value));
                        }),
                        FormButton.of(CANCEL, () -> Holograms.close(HologramDialogs.ID)))).wide()));
    }

    private static List<String> chainNames() {
        return Arrays.stream(ChainPlatform.values()).map(ChainPlatform::displayName).toList();
    }

    /**
     * The chain the field names. The field is authoritative and can be typed into, so a name that
     * matches nothing falls back to {@link ChainPlatform#OTHER}, the way in for a chain this
     * release has never heard of.
     */
    private static ChainPlatform platformOf(String typed) {
        String name = typed.trim();
        return Arrays.stream(ChainPlatform.values())
                .filter(candidate -> candidate.displayName().equalsIgnoreCase(name)
                        || candidate.name().equalsIgnoreCase(name.replace(' ', '_')))
                .findFirst()
                .orElse(ChainPlatform.OTHER);
    }

    /** What is wrong with the address, or empty when it may be stored. A blank one is wrong too. */
    private static Optional<String> problem(String chain, String address) {
        String value = address.trim();
        if (value.isEmpty()) {
            return Optional.of("");
        }
        return AddressFormats.problem(platformOf(chain), value);
    }

    private static String addressNotice(String chain, String address) {
        if (address.isBlank()) {
            return I18n.get(PREFIX + "address.public_only");
        }
        return problem(chain, address).orElseGet(() -> I18n.get(PREFIX + "address.looks_right"));
    }

    private static FormTone addressTone(String chain, String address) {
        if (address.isBlank()) {
            return FormTone.SOFT;
        }
        return problem(chain, address).isPresent() ? FormTone.BAD : FormTone.GOOD;
    }

    /**
     * Walks the player through proving addresses with MetaMask.
     *
     * <p>MetaMask is a browser extension, so this starts a small server on {@code 127.0.0.1},
     * opens the player's browser at it and waits. The page asks MetaMask to sign a one-off
     * message for every address it shares, and the mod checks each signature. The server stops
     * when the page has finished or when this hologram closes, whichever comes first: closing it
     * by any path stops the server, so there is no way to leave it running.
     *
     * <p>The link is shown as well as opened, because the browser that opens by default is often
     * not the one MetaMask is installed in. Signing costs nothing: it never reaches a chain,
     * moves no funds and approves nothing.
     *
     * @param onBound called with every address the browser proved, once it has finished
     */
    static void connect(String accountId, Consumer<List<WalletBinding>> onBound) {
        new Connect(accountId, onBound).start();
    }

    /** One MetaMask handshake and the hologram that shows it. */
    private static final class Connect {

        private final String accountId;
        private final Consumer<List<WalletBinding>> onBound;

        private LocalWalletBridge bridge;
        private String state = I18n.get(PREFIX + "connect.starting");
        private boolean failed;
        private boolean closed;

        private Connect(String accountId, Consumer<List<WalletBinding>> onBound) {
            this.accountId = accountId;
            this.onBound = onBound;
        }

        private void start() {
            Holograms.open(new Hologram(HologramDialogs.ID, List.of(new Form(PREFIX + "connect", List.of(
                    FormText.of(I18n.get(PREFIX + "connect.into", accountId), FormTone.SOFT, 1),
                    // Said before the player reaches their wallet: this is the moment a scam
                    // would ask for a recovery phrase, and the mod never will.
                    FormText.of(PREFIX + "connect.never_asks", FormTone.WARN, 2),
                    FormText.of(PREFIX + "connect.signing_is_free", FormTone.SOFT, 3),
                    new FormText(() -> state, () -> failed ? FormTone.BAD : FormTone.TEXT, 2),
                    new FormText(() -> bridge == null ? "" : bridge.url().toString(),
                            () -> FormTone.SOFT, 2)),
                    // Once the attempt has ended the bridge is gone and the link leads nowhere,
                    // so these grey out rather than send the player to a dead port.
                    List.of(new FormButton(PREFIX + "connect.open", () -> !failed, this::openBrowser),
                            new FormButton(PREFIX + "connect.copy", () -> !failed, this::copyLink),
                            FormButton.of(CANCEL, () -> Holograms.close(HologramDialogs.ID)))).wide()),
                    this::stop));
            try {
                bridge = LocalWalletBridge.start(accountId);
            } catch (RuntimeException e) {
                LOGGER.error("Could not start the wallet bridge", e);
                state = I18n.get(PREFIX + "connect.failed", HologramPage.describe(e));
                failed = true;
                return;
            }
            state = I18n.get(PREFIX + "connect.waiting");
            LocalWalletBridge started = bridge;
            started.bindings().whenComplete((bound, failure) -> Minecraft.getInstance().execute(() -> {
                if (closed || bridge != started) {
                    return;
                }
                if (failure != null) {
                    // The bridge's own wording already says what went wrong: a declined
                    // signature, a timeout, a closed tab.
                    LOGGER.info("The wallet connection did not complete: {}",
                            HologramPage.describe(failure));
                    state = HologramPage.describe(failure);
                    failed = true;
                    stopBridge();
                    return;
                }
                Holograms.close(HologramDialogs.ID);
                onBound.accept(bound);
            }));
            openBrowser();
        }

        private void openBrowser() {
            if (bridge != null && !SystemBrowser.open(bridge.url())) {
                // Not a failure of the binding: the link is shown and can be copied.
                state = I18n.get(PREFIX + "connect.no_browser");
            }
        }

        private void copyLink() {
            if (bridge != null) {
                Minecraft.getInstance().keyboardHandler.setClipboard(bridge.url().toString());
                state = I18n.get(PREFIX + "connect.copied");
            }
        }

        /** Runs whenever the hologram closes, by any path. */
        private void stop() {
            closed = true;
            stopBridge();
        }

        private void stopBridge() {
            if (bridge != null) {
                bridge.close();
                bridge = null;
            }
        }
    }
}
