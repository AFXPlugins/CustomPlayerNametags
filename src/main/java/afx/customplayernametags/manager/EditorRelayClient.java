package afx.customplayernametags.manager;

import afx.customplayernametags.CustomPlayerNametags;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * Talks to the free Cloudflare Worker + KV relay that backs
 * {@code /nametags editor web} — see {@code cloudflare-worker/worker.js} in
 * this project for the server side. This is the whole point of the "zero
 * setup" design: the plugin already knows both {@link #WORKER_URL} (where
 * to POST/GET sessions) and {@link #EDITOR_URL} (the GitHub Pages web
 * editor that reads them), so an admin never needs an account, an API key,
 * or port forwarding to use the web editor.
 *
 * <p>Every request runs on a Bukkit async task, exactly like
 * {@link afx.customplayernametags.update.UpdateChecker} — this is blocking
 * HTTP and must never touch the main thread. Callbacks are always invoked
 * back on the main thread afterward.
 *
 * <p>This client only ever creates and reads sessions. Nothing here can
 * push a command into the server — applying changes is a manual,
 * human-in-the-loop step (copy-pasting generated commands into the
 * console), by design.
 */
public final class EditorRelayClient {

    /**
     * The shared relay Worker every copy of this plugin talks to by
     * default. Self-hosting your own copy of {@code cloudflare-worker/}?
     * Change this (and {@link #EDITOR_URL}, if you also fork the web
     * editor) before building the jar — there is deliberately no
     * config.yml setting for this, so a fresh install always works with
     * zero configuration.
     */
    public static final String WORKER_URL = "https://customplayernametags-editor-relay.YOUR_SUBDOMAIN.workers.dev";

    /** The GitHub Pages web editor this plugin hands admins a link to. */
    public static final String EDITOR_URL = "https://YOUR_GITHUB_USERNAME.github.io/nametag-format-editor/";

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final CustomPlayerNametags plugin;
    private final HttpClient httpClient;

    public EditorRelayClient(CustomPlayerNametags plugin) {
        this.plugin = plugin;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT)
                .build();
    }

    /** Outcome of {@link #createSession}. */
    public static final class CreateResult {
        private final boolean success;
        private final String sessionId;
        private final String editorUrl;
        private final String failureReason;

        private CreateResult(boolean success, String sessionId, String editorUrl, String failureReason) {
            this.success = success;
            this.sessionId = sessionId;
            this.editorUrl = editorUrl;
            this.failureReason = failureReason;
        }

        static CreateResult ok(String sessionId, String editorUrl) {
            return new CreateResult(true, sessionId, editorUrl, null);
        }

        static CreateResult failed(String reason) {
            return new CreateResult(false, null, null, reason);
        }

        public boolean isSuccess() {
            return success;
        }

        public String getSessionId() {
            return sessionId;
        }

        /** The full web editor link — {@link #EDITOR_URL} with {@code ?session=<id>} appended. */
        public String getEditorUrl() {
            return editorUrl;
        }

        public String getFailureReason() {
            return failureReason;
        }
    }

    /**
     * POSTs {@code snapshot} to the relay Worker and invokes {@code callback}
     * on the main thread with the resulting session link (or a failure
     * reason). {@code snapshot} must already be fully built (see
     * {@link EditorSessionBuilder#build}) — building it requires the main
     * thread, but sending it does not, so build it first and pass the
     * finished {@link JsonObject} in here.
     */
    public void createSession(JsonObject snapshot, Consumer<CreateResult> callback) {
        String body = snapshot.toString();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            CreateResult result = doCreateSession(body);
            if (!plugin.isEnabled()) {
                // Server shutting down / plugin disabled mid-request — nobody
                // left to hand the link to, and scheduling would throw.
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> callback.accept(result));
        });
    }

    private CreateResult doCreateSession(String body) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(WORKER_URL + "/session"))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("User-Agent", "AFXPlugins/CustomPlayerNametags/" + plugin.getDescription().getVersion())
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 429) {
                return CreateResult.failed("the editor relay is rate-limiting this server right now — try again in a minute");
            }
            if (response.statusCode() == 413) {
                return CreateResult.failed("the nametag configuration snapshot was too large to relay");
            }
            if (response.statusCode() != 200 && response.statusCode() != 201) {
                return CreateResult.failed("relay returned HTTP " + response.statusCode());
            }

            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            if (!json.has("id") || json.get("id").isJsonNull()) {
                return CreateResult.failed("relay response was missing a session id");
            }
            String id = json.get("id").getAsString();
            String editorUrl = EDITOR_URL + (EDITOR_URL.endsWith("/") ? "" : "/") + "?session=" + id;
            return CreateResult.ok(id, editorUrl);
        } catch (IOException e) {
            return CreateResult.failed("could not reach the editor relay (" + e.getMessage() + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CreateResult.failed("request to the editor relay was interrupted");
        } catch (RuntimeException e) {
            return CreateResult.failed("unexpected response from the editor relay (" + e.getMessage() + ")");
        }
    }
}
