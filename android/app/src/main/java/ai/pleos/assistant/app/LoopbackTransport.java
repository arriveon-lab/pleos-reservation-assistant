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
    boolean preview = path.matches("/experimental/place-previews(/[a-zA-Z0-9-]+)*");
    if (!BuildConfig.DEBUG
        || (!preview
            && !path.matches(
                "/(status|searches|reservations|calls|anonymous-sessions)(/[a-zA-Z0-9-]+)*")))
      throw new IllegalStateException("LOCAL_DEBUG_ONLY");
    HttpURLConnection connection =
        (HttpURLConnection)
            new URL("http://127.0.0.1:8080/api" + (preview ? "" : "/v1") + path)
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
}
