package io.github.mcagentsmods.chat.shared.client;

import io.github.mcagentsmods.chat.api.agent.EnvironmentSource;
import io.github.mcagentsmods.chat.api.agent.PlayerEnvironment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Watches where the player is playing, so the agent can be told.
 *
 * <p>This is the mod's half of {@link EnvironmentSource}: the only place that knows what a
 * server list entry is. Each loader calls {@link #onJoin} when the player joins a world and
 * {@link #onLeave} when they leave; it records one value on the first and clears it on the
 * second, and {@code common/} reads that value while assembling a prompt and never
 * sees a Minecraft type.
 *
 * <h2>Recorded on the event, not read on demand</h2>
 *
 * <p>A prompt is assembled on a background thread. Reaching into {@link Minecraft} from
 * there to ask what server this is would be reading client state off the client thread —
 * the same race the screens exist to avoid, for a value that changes twice an hour at most.
 * The connection events already fire on the client thread, so the value is taken there and
 * published once.
 *
 * <h2>What counts as single player</h2>
 *
 * <p>{@code hasSingleplayerServer()} is true for a world opened from the single-player menu
 * — including one the player has since shared to LAN, because they are still its host and
 * still playing their own world. A player who <em>joins</em> somebody else's LAN world has
 * no integrated server and is treated as what they are: a client of a server, named by the
 * address they connected to.
 */
public final class GameEnvironment implements EnvironmentSource {

    private static final Logger LOGGER = LoggerFactory.getLogger(GameEnvironment.class);

    private static final AtomicReference<PlayerEnvironment> CURRENT =
            new AtomicReference<>(PlayerEnvironment.unknown());

    private static final GameEnvironment INSTANCE = new GameEnvironment();

    private GameEnvironment() {
    }

    /** The source to hand to anything below this module. */
    public static EnvironmentSource source() {
        return INSTANCE;
    }

    /**
     * The player joined a world. Called on the client thread by the loader's join event.
     *
     * <p>Each loader registers its listener beside chat's menu buttons, before storage is open:
     * registering a listener cannot fail and touches nothing on disk or on the network, and a
     * player may be in a world before the database is.
     */
    public static void onJoin(Minecraft client) {
        record(detect(client));
    }

    /**
     * The player left the world. Cleared rather than left standing: a question asked at the
     * main menu after a session must not carry the server the player has just left.
     */
    public static void onLeave() {
        record(PlayerEnvironment.unknown());
    }

    @Override
    public PlayerEnvironment current() {
        return CURRENT.get();
    }

    private static void record(PlayerEnvironment environment) {
        PlayerEnvironment previous = CURRENT.getAndSet(environment);
        if (!previous.equals(environment)) {
            // The address is written to the log the player already sees. It is theirs, they
            // typed it into the server list themselves, and the alternative — a mod that
            // silently changes what it sends the model — is worse.
            LOGGER.info("Player environment is now {}", describe(environment));
        }
    }

    private static PlayerEnvironment detect(Minecraft client) {
        if (client == null) {
            return PlayerEnvironment.unknown();
        }
        if (client.hasSingleplayerServer()) {
            return PlayerEnvironment.singlePlayer();
        }
        ServerData server = client.getCurrentServer();
        String address = server == null ? null : server.ip;
        if (address == null || address.isBlank()) {
            // Joined something with no server list entry behind it — a direct connection
            // the game has not filled in yet, or a flow the mod has not seen. Unknown is
            // the honest answer, and it costs the prompt one line rather than a wrong one.
            return PlayerEnvironment.unknown();
        }
        return PlayerEnvironment.multiplayer(address);
    }

    private static String describe(PlayerEnvironment environment) {
        return switch (environment.kind()) {
            case SINGLE_PLAYER -> "single player";
            case MULTIPLAYER -> "the server " + environment.serverAddress();
            case UNKNOWN -> "unknown";
        };
    }
}
