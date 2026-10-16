package io.github.hanshs474.emaki;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Plain-main tests so the build needs no test framework. Response bodies are the service's real shapes. */
public final class EmakiTest {
    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    @SuppressWarnings("unchecked")
    static Emaki.Response res(int status, String json) {
        return new Emaki.Response(status, (Map<String, Object>) Json.parse(json));
    }

    static EmakiException.Kind kindOf(int status, String body) {
        Emaki e = new Emaki("sk-test", "https://t", (url, json, key) -> res(status, body));
        try {
            e.submit("x", "seedance_2_5", null);
        } catch (EmakiException ex) {
            return ex.kind();
        }
        throw new AssertionError("no error for " + status);
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) {
        List<String> urls = new ArrayList<>();
        List<Map<String, Object>> bodies = new ArrayList<>();
        int[] polls = {0};
        Emaki.Transport t = (url, json, key) -> {
            check(key.equals("sk-test"), "bearer key");
            urls.add(url);
            bodies.add((Map<String, Object>) Json.parse(json));
            if (url.endsWith("/api/video/ai")) {
                return res(200, "{\"success\":true,\"data\":{\"id\":\"vid_1\",\"status\":\"generating\",\"model\":\"seedance_2_5\"}}");
            }
            if (polls[0]++ == 0) {
                return res(200, "{\"success\":true,\"data\":{\"id\":\"vid_1\",\"status\":\"generating\",\"videoUrl\":null,\"failureCode\":null}}");
            }
            return res(200, "{\"success\":true,\"data\":{\"id\":\"vid_1\",\"status\":\"completed\",\"videoUrl\":\"https://cdn.emaki.ai/v.mp4\",\"failureCode\":null}}");
        };
        Emaki e = new Emaki("sk-test", "https://t", t).pollEvery(Duration.ZERO);

        check(e.generateVideo("a fox in snow").equals("https://cdn.emaki.ai/v.mp4"), "video url");
        check(urls.get(0).equals("https://t/api/video/ai"), "submit path");
        check(bodies.get(0).get("model").equals("seedance_2_5"), "default video model");
        check(((Map<String, Object>) bodies.get(0).get("parameters")).isEmpty(), "empty parameters");
        check(urls.get(1).equals("https://t/api/video/status"), "status path");
        check(bodies.get(1).get("videoId").equals("vid_1"), "status body");
        check(urls.size() == 3, "polled until completed");

        urls.clear();
        bodies.clear();
        polls[0] = 1;
        e.generateImage("a ramen stall", null, Map.of("aspect_ratio", "16:9"));
        check(bodies.get(0).get("model").equals("nano_banana_2_lite"), "default image model");
        check(((Map<String, Object>) bodies.get(0).get("parameters")).get("aspect_ratio").equals("16:9"), "parameters passed");

        Emaki failing = new Emaki("sk-test", "https://t", (url, json, key) -> url.endsWith("/api/video/ai")
                ? res(200, "{\"success\":true,\"data\":{\"id\":\"vid_2\",\"status\":\"generating\"}}")
                : res(200, "{\"success\":true,\"data\":{\"id\":\"vid_2\",\"status\":\"failed\",\"videoUrl\":null,\"failureCode\":\"provider_error\"}}"))
                .pollEvery(Duration.ZERO);
        try {
            failing.generateVideo("x");
            check(false, "failed run must throw");
        } catch (EmakiException ex) {
            check(ex.kind() == EmakiException.Kind.FAILED && "provider_error".equals(ex.failureCode()), "failure code");
        }

        Emaki.Status s = new Emaki("sk-test", "https://t", (url, json, key) ->
                res(200, "{\"success\":true,\"data\":{\"id\":\"vid_3\",\"status\":\"generating\",\"videoUrl\":null}}")).status("vid_3");
        check(s.id.equals("vid_3") && !s.isCompleted() && s.videoUrl == null, "status object");

        Emaki c = new Emaki("sk-test", "https://t", (url, json, key) ->
                res(200, "{\"code\":0,\"message\":\"ok\",\"data\":{\"remainingCredits\":42,\"hasPurchasedCredits\":false}}"));
        check(c.credits() == 42, "credits");
        try {
            new Emaki("sk-bad", "https://t", (url, json, key) ->
                    res(200, "{\"code\":-1,\"message\":\"Invalid API key. Create one at https://emaki.ai/settings/apikeys\"}")).credits();
            check(false, "credits code -1 must throw");
        } catch (EmakiException ex) {
            check(ex.kind() == EmakiException.Kind.AUTH, "credits auth");
        }

        check(kindOf(401, "{\"error\":\"Invalid API key. Create one at https://emaki.ai/settings/apikeys\"}") == EmakiException.Kind.AUTH, "401");
        check(kindOf(402, "{\"error\":\"Insufficient credits\"}") == EmakiException.Kind.QUOTA, "402");
        check(kindOf(403, "{\"error\":\"This model requires a credit purchase\"}") == EmakiException.Kind.PLAN, "403");
        check(kindOf(409, "{\"error\":\"Video generation is already in progress\"}") == EmakiException.Kind.BUSY, "409");
        check(kindOf(429, "{\"error\":\"cooldown\",\"code\":\"content_cooldown\"}") == EmakiException.Kind.REJECTED, "429 cooldown");
        check(kindOf(400, "{\"error\":\"blocked\",\"code\":\"content_blocked\"}") == EmakiException.Kind.REJECTED, "400 blocked");
        check(kindOf(400, "{\"error\":\"duration must be between 4 and 30\"}") == EmakiException.Kind.VALIDATION, "400 validation");
        check(kindOf(502, "{\"error\":\"upstream\"}") == EmakiException.Kind.OTHER, "5xx");

        try {
            new Emaki("").submit("x", "seedance_2_5", null);
            check(false, "needs key");
        } catch (EmakiException ex) {
            check(ex.kind() == EmakiException.Kind.AUTH, "empty key");
        }
        System.out.println("all tests passed");
    }
}
