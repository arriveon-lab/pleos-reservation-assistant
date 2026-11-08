package ai.pleos.assistant.app;

import ai.pleos.assistant.ux.MockRepository;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

final class LoopbackTransport implements MockRepository.Transport {
  @Override
  public JSONObject exchange(String path, JSONObject body, String key) throws Exception {
    if (!BuildConfig.DEBUG
        || !path.matches("/(status|searches|reservations|calls|mock/calls)(/[a-zA-Z0-9-]+)*"))
      throw new IllegalStateException("LOCAL_DEBUG_ONLY");
    HttpURLConnection connection =
        (HttpURLConnection)
            new URL("http://127.0.0.1:8080/api/v1" + path).openConnection(Proxy.NO_PROXY);
    try {
      connection.setConnectTimeout(5000);
      connection.setReadTimeout(10000);
      connection.setInstanceFollowRedirects(false);
      connection.setUseCaches(false);
      connection.setRequestProperty("Accept", "application/json");
      if (body != null) {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(bytes.length);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Idempotency-Key", key);
        try (var output = connection.getOutputStream()) {
          output.write(bytes);
        }
      }
      int code = connection.getResponseCode();
      if (code != 200) throw new IllegalStateException("HTTP_" + code);
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
