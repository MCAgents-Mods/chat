package io.github.mcagents.clientfabric.website;

import io.github.mcagents.clientfabric.api.agent.Agent;
import io.github.mcagents.clientfabric.api.storage.LocalDatabase;
import io.github.mcagents.clientfabric.common.storage.sqlite.SqliteDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That the pages the console serves are actually reachable, and reference only things that
 * exist.
 *
 * <p>A broken asset path is the failure this catches. It cannot be seen in a Java test that
 * only calls the JSON API, it does not fail the build, and in a browser it produces a page
 * that loads with no styling at all — so every {@code href} and {@code src} in every page is
 * followed here and required to answer.
 */
class WebConsolePagesTest {

    private static final Pattern ASSET_REFERENCE =
            Pattern.compile("(?:href|src)=\"([^\"]+)\"");

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @TempDir
    Path gameDirectory;

    private LocalDatabase database;
    private LocalWebConsole console;

    @BeforeEach
    void open() {
        database = await(SqliteDatabase.open(gameDirectory.resolve("MCAgents/data.db")));
        console = LocalWebConsole.start(database,
                (sessionId, prompt) -> CompletableFuture.completedFuture("ok"));
    }

    @AfterEach
    void close() {
        if (console != null) {
            console.close();
        }
        if (database != null) {
            database.close();
        }
    }

    @Test
    void servesEveryPage() {
        assertEquals(200, statusOf(url("/knowledge/")));
        assertEquals(200, statusOf(url("/chat/")));
        // Any path under /knowledge/ is a pack id; the detail page reads it from the
        // address bar rather than being handed it by the server.
        assertEquals(200, statusOf(url("/knowledge/some-pack-id")));
        assertEquals(200, statusOf(url("/keys/")));
        assertEquals(200, statusOf(url("/model/")));
        assertEquals(200, statusOf(url("/wallets/")));
    }

    @Test
    void everyAssetEachPageReferencesActuallyExists() {
        for (String page : List.of("/knowledge/", "/chat/", "/knowledge/any-pack",
                "/keys/", "/model/", "/wallets/")) {
            String markup = body(url(page));
            Matcher matcher = ASSET_REFERENCE.matcher(markup);
            int checked = 0;

            while (matcher.find()) {
                String reference = matcher.group(1);
                if (reference.startsWith("#") || reference.startsWith("data:")) {
                    continue;
                }
                // Resolved the way a browser resolves it, against the page's own address.
                URI resolved = URI.create(base() + page).resolve(reference);
                assertEquals(200, statusOf(resolved.toString()),
                        page + " references " + reference + ", which the console does not serve");
                checked++;
            }
            assertTrue(checked >= 5, page + " should reference its stylesheets and scripts");
        }
    }

    @Test
    void servesStylesheetsAndScriptsWithoutATokenBecauseATagCannotCarryOne() {
        // A <link> in a static page has no query string. These files ship in the jar, are
        // the same for every player, and carry no data of theirs.
        assertEquals(200, statusOf(base() + "/assets/css/main.css"));
        assertEquals(200, statusOf(base() + "/assets/js/site.js"));
        assertEquals(200, statusOf(base() + "/assets/partials/header.html"));
    }

    @Test
    void stillGuardsThePagesAndTheDataBehindThem() {
        // The exemption is for assets only. Anything that reads the database, and every
        // page that would then ask for it, stays behind the token.
        assertEquals(403, statusOf(base() + "/knowledge/"));
        assertEquals(403, statusOf(base() + "/chat/"));
        assertEquals(403, statusOf(base() + "/api/chats"));
        assertEquals(403, statusOf(base() + "/api/knowledge"));
    }

    @Test
    void servesPagesThatFetchNothingFromOffTheMachine() {
        // The design system's hard rule. A page that reaches for a CDN renders unstyled on
        // the machine that needed it most.
        for (String page : List.of("/knowledge/", "/chat/", "/knowledge/any-pack",
                "/keys/", "/model/", "/wallets/")) {
            String markup = body(url(page));
            assertFalse(markup.contains("http://") || markup.contains("https://"),
                    page + " must not reference anything off this machine");
        }
        for (String asset : List.of("/assets/css/main.css", "/assets/js/site.js",
                "/assets/js/chat.js", "/assets/js/knowledge.js", "/assets/js/keys.js",
                "/assets/js/model.js", "/assets/css/shared/components.css")) {
            String source = body(base() + asset);
            assertFalse(source.contains("https://") || source.contains("cdn."),
                    asset + " must not fetch anything off this machine");
        }
    }

    @Test
    void asksForAKeysPlatformWithASelectorThatStillTakesAnythingTyped() {
        String markup = body(url("/keys/"));

        assertTrue(markup.contains("<select class=\"field field--platform\" id=\"key-platform\">"),
                "the platform is picked from a list, not spelled from memory");
        assertFalse(markup.contains("<datalist"),
                "the datalist it replaced is gone — invisible until typed into, and it read "
                        + "as a closed list while being nothing of the kind");
        assertTrue(markup.contains("id=\"key-platform-other\""),
                "and the list is still not a whitelist: a platform this release has never "
                        + "heard of is typed into the box beside it");
    }

    @Test
    void asksWhoAnswersOnTheChatPageItself() {
        String markup = body(url("/chat/"));

        assertTrue(markup.contains("id=\"chat-provider\""),
                "the provider that answers is chosen beside the question, not only on /model/");
        assertTrue(markup.contains("id=\"chat-provider-other\""),
                "and a provider this release has never heard of can still be named");
        assertTrue(markup.contains("id=\"chat-route\""),
                "with the badge that says whether it is reached directly or through the gateway");
    }

    @Test
    void putsNoPlayerDataInTheMarkupItself() {
        // The pages are static and fetch what they show, which is what keeps player text
        // out of a file nobody escapes.
        await(database.chat().createSession("A conversation title"));

        assertFalse(body(url("/chat/")).contains("A conversation title"));
    }

    // -------------------------------------------------------------- helpers

    private String base() {
        return "http://127.0.0.1:" + console.port();
    }

    private String url(String path) {
        return base() + path + "?t=" + console.url().getQuery().substring("t=".length());
    }

    private int statusOf(String uri) {
        return send(uri).statusCode();
    }

    private String body(String uri) {
        return send(uri).body();
    }

    private HttpResponse<String> send(String uri) {
        try {
            return client.send(HttpRequest.newBuilder()
                            .uri(URI.create(uri))
                            .timeout(Duration.ofSeconds(15))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("The console did not answer " + uri, e);
        }
    }

    private static <T> T await(CompletableFuture<T> future) {
        return future.orTimeout(10, TimeUnit.SECONDS).join();
    }
}
