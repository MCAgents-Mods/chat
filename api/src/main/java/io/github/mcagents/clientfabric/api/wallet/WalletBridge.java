package io.github.mcagents.clientfabric.api.wallet;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The short-lived handshake between the game and MetaMask in the player's browser.
 *
 * <h2>Why a browser is involved at all</h2>
 *
 * <p>MetaMask is a browser extension. It has no Java API, no local socket, and no way for a
 * desktop program to speak to it directly — {@code window.ethereum} exists only on a web
 * page. So the mod becomes a web page's server for as long as the handshake takes: it
 * listens on the loopback interface, hands the player's browser one page, and that page
 * asks MetaMask to sign a one-off message and posts the signature back.
 *
 * <h2>What this is not</h2>
 *
 * <p>It is <strong>not a server the mod runs</strong>. It binds to {@code 127.0.0.1} on a
 * port the operating system picks, it answers only requests carrying the one-time token in
 * {@link #url()}, and <strong>it stops as soon as the browser says it has finished</strong>
 * — or when the player cancels, or when the timeout passes, whichever comes first. Nothing
 * outside the machine can reach it and nothing keeps it alive.
 *
 * <p>One handshake, but not one address. A browser holds as many accounts as the player has
 * connected, and asking them to make this trip once per address was making them do by hand
 * what the page can do in a loop — so the page signs every address in turn and the bridge
 * collects them all before it stops.
 *
 * <p>It is also <strong>not a network dependency</strong>. Every wallet already bound stays
 * readable and every screen stays usable with no internet at all; only binding a new one
 * needs a browser, and failing to bind one never takes anything else down.
 *
 * <p>Implementations are {@link AutoCloseable} and closing is idempotent. Close the bridge
 * whatever happens — a screen the player walked away from must not leave a listener behind.
 */
public interface WalletBridge extends AutoCloseable {

    /**
     * Where to send the player's browser, including the one-time token.
     *
     * <p>Worth showing on screen as well as opening: a player whose default browser is not
     * the one MetaMask is installed in needs to paste this somewhere else.
     */
    URI url();

    /**
     * Completes with every address the player proved, in the order they were signed.
     *
     * <p>Completes exceptionally with a {@link WalletBridgeException} when the attempt ended
     * with nothing proved: the player cancelled before signing anything, the timeout passed,
     * or the screen closed. A signature that does not match the address it arrived with is
     * refused on the spot and simply never joins the list.
     *
     * <p><strong>What was proved is kept.</strong> An attempt that ends early after two of
     * five addresses were signed completes with those two rather than throwing them away:
     * they were proved, and discarding them would only mean asking the player to sign them
     * again. The list never contains an unverified address.
     */
    CompletableFuture<List<WalletBinding>> bindings();

    /**
     * Stops listening. Safe to call twice, and safe to call after the binding has completed.
     *
     * <p>A pending {@link #bindings()} is settled rather than left hanging — with whatever
     * was already proved, or exceptionally if nothing was. A screen waiting on a future
     * nobody will finish is a screen that waits forever.
     */
    @Override
    void close();
}
