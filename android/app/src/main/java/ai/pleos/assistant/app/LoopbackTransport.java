package ai.pleos.assistant.app;

import ai.pleos.assistant.ux.AuthSession;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

final class LoopbackTransport implements AuthSession.Wire {
  private final java.util.function.BooleanSupplier inputAllowed;

  LoopbackTransport(java.util.function.BooleanSupplier inputAllowed) {
    this.inputAllowed = inputAllowed;
  }

  @Override
  public JSONObject exchange(String path, JSONObject body, String key, String bearer)
      throws Exception {
    boolean destination =
        path.matches("/experimental/destination-recommendations(/[a-zA-Z0-9-]+)*");
    boolean preview = destination || path.matches("/experimental/place-previews(/[a-zA-Z0-9-]+)*");
    if (!BuildConfig.DEBUG
        || (!preview
            && !path.matches(
                "/(status|searches|reservations|calls|anonymous-sessions)(/[a-zA-Z0-9-]+)*")))
      throw new IllegalStateException("LOCAL_DEBUG_ONLY");
    if (BuildConfig.REAL_PLACE_DEMO) {
      boolean permitted =
          path.equals("/status")
              || path.equals("/searches/reconcile")
              || path.matches("/searches/[a-f0-9-]{36}")
              || path.equals("/anonymous-sessions")
              || path.equals("/anonymous-sessions/refresh")
              || path.equals("/experimental/destination-recommendations")
              || path.matches("/experimental/destination-recommendations/[a-f0-9-]{36}")
              || path.matches(
                  "/experimental/destination-recommendations/views/[a-f0-9-]{36}/close");
      if (!permitted) throw new IllegalStateException("RECOMMENDATION_ONLY");
    } else if (BuildConfig.EC2_MOCK_DEMO) {
      if (!BuildConfig.MOCK_PHONE_DEMO) throw new IllegalStateException("MOCK_DEMO_REQUIRED");
      requireEc2Mock();
    } else if (destination) requireDestinationMock(path.endsWith("/phone-reservations"));
    HttpURLConnection connection =
        (HttpURLConnection)
            new URL(
                    (BuildConfig.REAL_PLACE_DEMO
                            ? "http://127.0.0.1:8083"
                            : BuildConfig.EC2_MOCK_DEMO
                                ? "https://43.201.115.170/pleos-mock"
                                : "http://127.0.0.1:" + (destination ? "8081" : "8080"))
                        + "/api"
                        + (preview ? "" : "/v1")
                        + path)
                .openConnection(Proxy.NO_PROXY);
    try {
      connection.setConnectTimeout(5000);
      connection.setReadTimeout(10000);
      connection.setInstanceFollowRedirects(false);
      connection.setUseCaches(false);
      connection.setRequestProperty("Accept", "application/json");
      if (bearer != null) connection.setRequestProperty("Authorization", "Bearer " + bearer);
      if (body != null) {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(bytes.length);
        connection.setRequestProperty("Content-Type", "application/json");
        if (key != null) connection.setRequestProperty("Idempotency-Key", key);
        // Cancellation and same-subject token refresh are cleanup/recovery, not new consent.
        boolean cleanup =
            path.endsWith("/cancel")
                || path.endsWith("/close")
                || path.equals("/anonymous-sessions/refresh");
        if (!cleanup && !inputAllowed.getAsBoolean())
          throw new IllegalStateException("INPUT_BLOCKED");
        try (var output = connection.getOutputStream()) {
          output.write(bytes);
        }
      }
      int code = connection.getResponseCode();
      if (code != 200) {
        String errorCode = "";
        try (InputStream input = connection.getErrorStream()) {
          if (input != null) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            int value;
            while (buffer.size() <= 4096 && (value = input.read()) != -1) buffer.write(value);
            byte[] error = buffer.toByteArray();
            if (error.length <= 4096)
              errorCode =
                  new JSONObject(new String(error, StandardCharsets.UTF_8)).optString("code");
          }
        } catch (Exception unreadable) {
          // Keep the HTTP failure even if its optional JSON body is absent or malformed.
        }
        throw new AuthSession.HttpFailure(code, errorCode);
      }
      try (InputStream input = connection.getInputStream();
          ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) {
          if (bytes.size() + count > 262144) throw new IllegalStateException("RESPONSE_TOO_LARGE");
          bytes.write(buffer, 0, count);
        }
        return new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
      }
    } finally {
      connection.disconnect();
    }
  }

  // Fixed #56 trial deployment. Keep platform TLS/hostname validation and reject redirects.
  // Its handoff explicitly specifies PUBLIC_CATALOG and fictional phone scenarios.
  private void requireEc2Mock() throws Exception {
    HttpURLConnection c =
        (HttpURLConnection)
            new URL("https://43.201.115.170/pleos-mock/api/v1/status")
                .openConnection(Proxy.NO_PROXY);
    try {
      c.setConnectTimeout(5000);
      c.setReadTimeout(5000);
      c.setInstanceFollowRedirects(false);
      c.setUseCaches(false);
      if (c.getResponseCode() != 200) throw new IllegalStateException("EC2_MOCK_REQUIRED");
      try (InputStream in = c.getInputStream()) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int value;
        while (out.size() <= 4096 && (value = in.read()) != -1) out.write(value);
        byte[] bytes = out.toByteArray();
        if (bytes.length > 4096) throw new IllegalStateException("EC2_MOCK_REQUIRED");
        JSONObject status = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        if (!"MOCK".equals(status.optString("mode"))
            || !"FICTIONAL_FIXTURES".equals(status.optString("dataSource"))
            || !"1.1.0".equals(status.optString("contractVersion"))
            || status.optBoolean("externalCallsEnabled", true))
          throw new IllegalStateException("EC2_MOCK_REQUIRED");
      }
    } finally {
      c.disconnect();
    }
  }

  // #53 can execute paid web calls even with the reservation service in MOCK mode. Only the
  // explicitly marked, separate test harness is accepted; stock/live servers fail closed.
  private void requireDestinationMock(boolean phone) throws Exception {
    HttpURLConnection c =
        (HttpURLConnection)
            new URL("http://127.0.0.1:8081/mock-capabilities").openConnection(Proxy.NO_PROXY);
    try {
      c.setConnectTimeout(3000);
      c.setReadTimeout(3000);
      c.setInstanceFollowRedirects(false);
      if (c.getResponseCode() != 200) throw new IllegalStateException("DESTINATION_MOCK_REQUIRED");
      try (InputStream in = c.getInputStream()) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int value;
        while (buffer.size() < 4097 && (value = in.read()) != -1) buffer.write(value);
        byte[] bytes = buffer.toByteArray();
        if (bytes.length > 4096) throw new IllegalStateException("DESTINATION_MOCK_REQUIRED");
        JSONObject marker = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        if (!"MOCK".equals(marker.getString("destinationRecommendationsMode"))
            || marker.getBoolean("externalCallsEnabled")
            || (phone
                && (!BuildConfig.MOCK_PHONE_DEMO
                    || !"MOCK".equals(marker.optString("phoneReservationsMode")))))
          throw new IllegalStateException("DESTINATION_MOCK_REQUIRED");
      }
    } finally {
      c.disconnect();
    }
  }
}
