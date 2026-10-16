package io.github.hanshs474.emaki;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Official SDK for <a href="https://emaki.ai">emaki</a> (emaki.ai), the Japanese-language AI video
 * generator for Seedance 2.5 and Seedance 2.0.
 *
 * <pre>{@code
 * Emaki emaki = new Emaki(System.getenv("EMAKI_API_KEY"));
 * String clip = emaki.generateVideo("a paper lantern drifting down a night river, slow dolly");
 * String image = emaki.generateImage("a ramen stall in the rain, neon reflections");
 * int left = emaki.credits();
 * }</pre>
 *
 * <p>Create a key at https://emaki.ai/settings/apikeys. Every run costs credits on your emaki
 * account, the same price as on the website. Java 11+, no dependencies.
 */
public final class Emaki {
    /** The service this library talks to. */
    public static final String BASE_URL = "https://emaki.ai";
    /** Where an API key is created. */
    public static final String KEYS_URL = BASE_URL + "/settings/apikeys";
    /** The default video model. */
    public static final String MODEL_VIDEO = "seedance_2_5";
    /** The default image model, the cheapest run on the site. */
    public static final String MODEL_IMAGE = "nano_banana_2_lite";
    /** Library version, sent in the User-Agent. */
    public static final String VERSION = "0.1.0";

    /** Where a submitted run stands. */
    public static final class Status {
        /** The run id. */
        public final String id;
        /** {@code generating}, {@code completed} or {@code failed}. */
        public final String status;
        /** The finished file (video or image) once {@code completed}, else null. */
        public final String videoUrl;
        /** Why the run failed, when it did and the service said. */
        public final String failureCode;
        /** The whole {@code data} object as the service sent it. */
        public final Map<String, Object> raw;

        Status(Map<String, Object> d) {
            this.raw = Collections.unmodifiableMap(d);
            this.id = str(d.get("id"));
            this.status = str(d.get("status"));
            this.videoUrl = str(d.get("videoUrl"));
            this.failureCode = str(d.get("failureCode"));
        }

        /** True once the file is ready. */
        public boolean isCompleted() {
            return "completed".equals(status);
        }

        /** True when the run failed. */
        public boolean isFailed() {
            return "failed".equals(status);
        }

        @Override
        public String toString() {
            return "Status{id=" + id + ", status=" + status + ", videoUrl=" + videoUrl + "}";
        }
    }

    /** A parsed HTTP answer. */
    static final class Response {
        final int status;
        final Map<String, Object> body;

        Response(int status, Map<String, Object> body) {
            this.status = status;
            this.body = body;
        }
    }

    /** Transport seam: POST a JSON body, return status and parsed JSON. */
    interface Transport {
        Response post(String url, String json, String key) throws IOException, InterruptedException;
    }

    private final String apiKey;
    private final String base;
    private final Transport transport;
    private Duration timeout = Duration.ofMinutes(15);
    private Duration pollEvery = Duration.ofSeconds(5);

    /** A client that reads the key from the {@code EMAKI_API_KEY} environment variable. */
    public Emaki() {
        this(System.getenv("EMAKI_API_KEY"));
    }

    /** A client for one API key. */
    public Emaki(String apiKey) {
        this(apiKey, BASE_URL, null);
    }

    Emaki(String apiKey, String base, Transport transport) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.base = base;
        this.transport = transport != null ? transport : httpTransport();
    }

    /** How long {@code generate*} waits for a run. Defaults to 15 minutes, which covers a queued video. */
    public Emaki timeout(Duration v) {
        timeout = v;
        return this;
    }

    /** How often {@code generate*} asks for the status. Defaults to five seconds. */
    public Emaki pollEvery(Duration v) {
        pollEvery = v;
        return this;
    }

    private static Transport httpTransport() {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
        return (url, json, key) -> {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + key)
                    .header("User-Agent", "emaki-java/" + VERSION)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            Object parsed;
            try {
                parsed = Json.parse(res.body());
            } catch (RuntimeException e) {
                parsed = null;
            }
            return new Response(res.statusCode(), asMap(parsed));
        };
    }

    /** Generate one clip with Seedance 2.5 (5 seconds, 480p, 16:9, with sound) and return its URL. */
    public String generateVideo(String prompt) {
        return generateVideo(prompt, MODEL_VIDEO, null);
    }

    /**
     * Generate one clip and return its URL.
     *
     * @param model e.g. {@code seedance_2_5}, {@code seedance_2}, {@code seedance_2_fast}, {@code seedance_2_mini}, {@code minimax_h3}, {@code veo_3_1}
     * @param parameters e.g. {@code Map.of("duration", 8, "resolution", "720p", "aspect_ratio", "9:16", "image", "https://...")}; null for the defaults
     */
    public String generateVideo(String prompt, String model, Map<String, ?> parameters) {
        return waitFor(submit(prompt, model == null ? MODEL_VIDEO : model, parameters));
    }

    /** Generate one image with Nano Banana 2 Lite and return its URL. */
    public String generateImage(String prompt) {
        return generateImage(prompt, MODEL_IMAGE, null);
    }

    /**
     * Generate one image and return its URL.
     *
     * @param model e.g. {@code nano_banana_2_lite}, {@code nano_banana_2}, {@code seedream_5_lite}, {@code gpt_image_2}
     * @param parameters e.g. {@code Map.of("aspect_ratio", "16:9")}; null for the defaults
     */
    public String generateImage(String prompt, String model, Map<String, ?> parameters) {
        return waitFor(submit(prompt, model == null ? MODEL_IMAGE : model, parameters));
    }

    /** Start a run without waiting and return its id; follow it with {@link #status(String)}. */
    public String submit(String prompt, String model, Map<String, ?> parameters) {
        if (prompt == null || prompt.trim().isEmpty()) throw new IllegalArgumentException("prompt is required");
        if (model == null || model.isEmpty()) throw new IllegalArgumentException("model is required");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("prompt", prompt);
        body.put("model", model);
        body.put("parameters", parameters == null ? new LinkedHashMap<String, Object>() : parameters);
        Map<String, Object> data = asMap(ok(post("/api/video/ai", body)).get("data"));
        Object id = data.get("id");
        if (id == null) throw new EmakiException(EmakiException.Kind.OTHER, "the service returned no run id");
        return id.toString();
    }

    /** Ask where a run stands. */
    public Status status(String id) {
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("id is required");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("videoId", id);
        return new Status(asMap(ok(post("/api/video/status", body)).get("data")));
    }

    /** The credits left on the account. Free to call. */
    public int credits() {
        Response r = post("/api/user/get-user-credits", new LinkedHashMap<>());
        if (r.status >= 400) throw error(r);
        // This endpoint answers HTTP 200 with code -1 on failure; the status alone reads as success.
        Object code = r.body.get("code");
        if (!(code instanceof Number) || ((Number) code).intValue() != 0) {
            String msg = str(r.body.get("message"));
            if (msg == null) msg = "request refused";
            EmakiException.Kind kind = msg.toLowerCase().contains("api key") ? EmakiException.Kind.AUTH : EmakiException.Kind.OTHER;
            throw new EmakiException(kind, msg, r.status, null, null);
        }
        Object left = asMap(r.body.get("data")).get("remainingCredits");
        if (!(left instanceof Number)) throw new EmakiException(EmakiException.Kind.OTHER, "the service returned no credit balance");
        return ((Number) left).intValue();
    }

    private String waitFor(String id) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            try {
                Thread.sleep(pollEvery.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new EmakiException(EmakiException.Kind.OTHER, "interrupted");
            }
            Status s;
            try {
                s = status(id);
            } catch (EmakiException e) {
                if (e.kind() == EmakiException.Kind.AUTH) throw e;
                continue; // a dropped poll is not a failed run
            }
            if (s.isCompleted() && s.videoUrl != null) return s.videoUrl;
            if (s.isFailed()) {
                String why = s.failureCode == null ? "unknown" : s.failureCode;
                throw new EmakiException(EmakiException.Kind.FAILED, "run " + id + " failed: " + why, 200, null, s.failureCode);
            }
        }
        throw new EmakiException(EmakiException.Kind.TIMEOUT,
                "deadline passed; run " + id + " may still finish in your emaki library");
    }

    private Map<String, Object> ok(Response r) {
        if (r.status >= 400) throw error(r);
        if (Boolean.FALSE.equals(r.body.get("success"))) {
            String msg = str(r.body.get("error"));
            throw new EmakiException(EmakiException.Kind.OTHER, msg == null ? "request refused" : msg, r.status, str(r.body.get("code")), null);
        }
        return r.body;
    }

    private static EmakiException error(Response r) {
        String msg = str(r.body.get("error"));
        if (msg == null) msg = str(r.body.get("message"));
        if (r.status == 401 && msg == null) msg = "an API key is required; create one at " + KEYS_URL;
        return EmakiException.fromHttp(r.status, msg, str(r.body.get("code")));
    }

    private Response post(String path, Map<String, Object> body) {
        if (apiKey.isEmpty()) {
            throw new EmakiException(EmakiException.Kind.AUTH, "an API key is required; create one at " + KEYS_URL);
        }
        try {
            return transport.post(base + path, Json.write(body), apiKey);
        } catch (IOException e) {
            throw new EmakiException(EmakiException.Kind.OTHER, e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EmakiException(EmakiException.Kind.OTHER, "interrupted");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : new HashMap<>();
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
