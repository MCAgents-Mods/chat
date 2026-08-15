package io.github.mcagents.clientfabric.api.web;

import java.net.URI;
import java.util.concurrent.CompletableFuture;

/**
 * The local web console: the player's own data, in their own browser, served from their own
 * machine.
 *
 * <h2>Why this exists when the game already has screens</h2>
 *
 * <p>A Minecraft screen is a good place to ask a question and a poor place to read a
 * thousand-word Knowledge Pack or scan a year of conversations. A browser has a scrollbar,
 * a find bar, real text selection, and a window the player can make as large as their
 * monitor. So the console does not replace the in-game screens — it is the same data, for
 * the jobs the game window is bad at.
 *
 * <h2>How this differs from the wallet bridge, deliberately</h2>
 *
 * <p>{@code WalletBridge} is a listener that exists for exactly one handshake and stops the
 * moment a signature verifies; anything that lets it outlive that is a defect. <strong>This
 * is not that.</strong> The console is a server the player opens on purpose and closes on
 * purpose, and it stays up in between because a browser tab they are reading is worthless
 * if the port closes under it.
 *
 * <p>The two share their defences and nothing else. Do not "fix" one to match the other.
 *
 * <h2>What keeps it safe</h2>
 *
 * <ul>
 *   <li><strong>Loopback only</strong>, on a port the operating system picks. Nothing off
 *       the machine can reach it.</li>
 *   <li><strong>A one-time token</strong>, minted per run and required on every request.
 *       Another program on the machine cannot guess it; another web page cannot read it.</li>
 *   <li><strong>A loopback {@code Host} header</strong>, which is what closes DNS
 *       rebinding.</li>
 *   <li><strong>A key is never handed back.</strong> The console can list, add, and remove
 *       API keys, because a player reading their conversations in a browser can reasonably
 *       expect to manage a key there too. What it cannot do is return one: keys are listed
 *       as masks and opaque handles, and no route, parameter, or flag yields a value. Adding
 *       one travels inward over loopback, exactly as typing it into the game does.</li>
 * </ul>
 *
 * <p>It also never reaches the network. Serving pages, listing packs, and reading history
 * are local operations; only asking the agent a new question leaves the machine, and that
 * is the player's own key going to the player's own provider, exactly as it does in game.
 */
public interface WebConsole extends AutoCloseable {

    /**
     * Where to send the browser, including the one-time token.
     *
     * <p>Worth showing on screen as well as opening, because the browser that opens by
     * default is not always the one the player wants to read in.
     */
    URI url();

    /** The loopback port it is listening on. */
    int port();

    /** Whether it is still listening. */
    boolean isRunning();

    /**
     * Stops the server. Safe to call twice.
     *
     * <p>Requests already in flight are given a moment to finish, so a page that is
     * mid-load does not simply hang.
     */
    @Override
    void close();

    /**
     * Completes when the server has stopped, however it stopped.
     *
     * <p>Lets a screen show "running" or "stopped" without polling for it.
     */
    CompletableFuture<Void> stopped();
}
