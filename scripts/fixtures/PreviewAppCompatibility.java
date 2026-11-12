import ai.pleos.assistant.ux.AuthSession;
import ai.pleos.assistant.ux.PreviewController;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

/**
 * JVM compatibility only: pinned app core, memory vault, bounded local wire. No Android runtime.
 */
public final class PreviewAppCompatibility {
  private static void require(boolean value) {
    if (!value) throw new IllegalStateException("APP_COMPATIBILITY_ASSERTION");
  }

  private static final class MemoryVault implements AuthSession.Vault {
    private String value;

    public String read() {
      return value;
    }

    public void save(String value) {
      this.value = value;
    }
  }

  private static final class Wire implements AuthSession.Wire {
    final String base;
    final AtomicInteger requests = new AtomicInteger(),
        queries = new AtomicInteger(),
        identities = new AtomicInteger();

    Wire(String base) {
      URI uri = URI.create(base);
      require(
          "http".equals(uri.getScheme())
              && "127.0.0.1".equals(uri.getHost())
              && uri.getPort() > 0
              && uri.getUserInfo() == null
              && uri.getRawQuery() == null
              && uri.getFragment() == null
              && uri.getPath().isEmpty());
      this.base = base;
    }

    @Override
    public JSONObject exchange(String path, JSONObject body, String key, String bearer)
        throws Exception {
      // This harness cannot send reservation/call/operator requests, even if the client changes.
      boolean preview =
          path.equals(PreviewController.ROOT)
              || path.matches("/experimental/place-previews/[a-f0-9-]{36}/(queries|cancel|close)");
      require(preview || path.matches("/(status|anonymous-sessions(/refresh|/current)?)"));
      requests.incrementAndGet();
      if (path.endsWith("/queries")) queries.incrementAndGet();
      if (path.equals("/anonymous-sessions")) identities.incrementAndGet();
      var connection =
          (HttpURLConnection)
              URI.create(base + "/api" + (preview ? "" : "/v1") + path)
                  .toURL()
                  .openConnection(Proxy.NO_PROXY);
      try {
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        connection.setRequestMethod(
            path.equals("/status") ? "GET" : path.endsWith("/current") ? "DELETE" : "POST");
        if (bearer != null) connection.setRequestProperty("Authorization", "Bearer " + bearer);
        if (key != null) connection.setRequestProperty("Idempotency-Key", key);
        if (body != null) {
          connection.setDoOutput(true);
          connection.setRequestProperty("Content-Type", "application/json");
          try (var output = connection.getOutputStream()) {
            output.write(body.toString().getBytes(StandardCharsets.UTF_8));
          }
        }
        int status = connection.getResponseCode();
        if (status != 200) throw new AuthSession.HttpFailure(status);
        require("no-store".equals(connection.getHeaderField("Cache-Control")));
        try (var input = connection.getInputStream()) {
          byte[] bytes = input.readNBytes(262145);
          require(bytes.length <= 262144);
          return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        }
      } finally {
        connection.disconnect();
      }
    }
  }

  private static PreviewController.View await(PreviewController controller, String expected)
      throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while (System.nanoTime() < deadline) {
      var view = controller.view();
      if (!"LOADING".equals(view.state())) {
        require(expected.equals(view.state()));
        return view;
      }
      Thread.sleep(10);
    }
    throw new IllegalStateException("APP_COMPATIBILITY_DEADLINE");
  }

  private static void cleanup(PreviewController controller) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while (controller.view().cleanupPending() && System.nanoTime() < deadline) Thread.sleep(10);
    require(!controller.view().cleanupPending() && !controller.view().cleanupFailed());
  }

  private static void result(PreviewController.View view, String state, int expected)
      throws Exception {
    if (state.equals("FAILED")) require(view.result() == null);
    else {
      require(view.result() != null && view.result().getJSONArray("places").length() == expected);
      var candidates = view.result().getJSONObject("recommendationPlan").getJSONArray("candidates");
      require(candidates.length() == expected);
      for (int i = 0; i < candidates.length(); i++) {
        var candidate = candidates.getJSONObject(i);
        require(
            !candidate.getBoolean("bookable") && !candidate.getBoolean("inquiryDispatchAllowed"));
      }
    }
  }

  public static void main(String[] args) {
    try {
      require(args.length == 3);
      var wire = new Wire(args[0]);
      String expected = args[1];
      int count = Integer.parseInt(args[2]);
      var session = new AuthSession(wire, new MemoryVault());
      session.createIdentity();
      String binding = session.binding();
      try (var controller = new PreviewController(session::exchangeTransient, () -> {})) {
        controller.allow(true);
        controller.search(new PreviewController.Input(2, 1, 15, true, true, "양식,한식", 0, 1000));
        result(await(controller, expected), expected, count);
        session.renewIdentity();
        require(binding.equals(session.binding()));
        controller.allow(false);
        require(
            "RESTRICTED".equals(controller.view().state()) && controller.view().result() == null);
        cleanup(controller);
        controller.allow(true);
        controller.search(new PreviewController.Input(20, 10, 15, true, true, "양식,한식", 0, 1000));
        result(await(controller, expected), expected, count);
        controller.invalidate("EDITING");
        require(controller.view().result() == null);
        cleanup(controller);
      }
      session.exchange("/anonymous-sessions/current", null, null);
      require(wire.identities.get() == 1 && wire.queries.get() == 2);
      System.out.println(
          "APP_COMPAT_RESULT "
              + new JSONObject()
                  .put("fixture", expected)
                  .put("requests", wire.requests.get())
                  .put("queries", wire.queries.get())
                  .put("identityIssues", wire.identities.get())
                  .put("mode", "MOCK"));
    } catch (Exception failed) {
      // Neither exceptions nor JSON assertions may reveal access/refresh tokens or page data.
      System.err.println("FAIL: pinned app core compatibility; response bodies omitted");
      System.exit(1);
    }
  }
}
