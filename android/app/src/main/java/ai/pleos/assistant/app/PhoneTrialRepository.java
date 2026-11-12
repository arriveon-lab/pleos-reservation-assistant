package ai.pleos.assistant.app;

import ai.pleos.assistant.ux.AuthSession;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** Independent phone-test cookie and journal. Never reads or changes MOCK reservation records. */
final class PhoneTrialRepository {
  interface Wire {
    JSONObject exchange(String path, JSONObject body, JSONObject session) throws Exception;
  }

  private final AuthSession.Vault vault;
  private final Wire wire;
  private final boolean dialingEnabled;
  private JSONObject session;
  JSONObject state = new JSONObject();
  JSONObject registration = new JSONObject();

  PhoneTrialRepository(AuthSession.Vault vault, Wire wire) throws Exception {
    this(vault, wire, BuildConfig.PHONE_TRIAL_DIAL);
  }

  PhoneTrialRepository(AuthSession.Vault vault, Wire wire, boolean dialingEnabled)
      throws Exception {
    this.vault = vault;
    this.wire = wire;
    this.dialingEnabled = dialingEnabled;
    String stored = vault.read();
    session = stored == null ? new JSONObject() : new JSONObject(stored);
  }

  boolean connected() {
    return session.has("cookie");
  }

  boolean pending() {
    return session.has("pending");
  }

  boolean credentials() {
    return session.has("authorization");
  }

  void login(String username, String password) throws Exception {
    if (connected() || pending()) throw new IllegalStateException("SESSION_PRESERVED");
    if (username.isBlank() || username.contains(":") || password.isEmpty())
      throw new IllegalStateException("LOGIN_REQUIRED");
    session.put(
        "authorization",
        "Basic "
            + Base64.getEncoder()
                .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8)));
    vault.save(session.toString());
    refresh();
  }

  void refresh() throws Exception {
    if (!credentials()) throw new IllegalStateException("LOGIN_REQUIRED");
    state = wire.exchange("/api/state", null, session);
    vault.save(session.toString());
    registration = wire.exchange("/api/live/callback-test-target", null, session);
  }

  void register(String phone) throws Exception {
    if (!phone.matches("010[0-9]{8}")) throw new IllegalStateException("INVALID_TEST_PHONE");
    send(
        "/api/live/callback-test-target",
        new JSONObject().put("action", "START").put("phoneNumber", phone));
  }

  void dial(String bookingId, int version, String phone) throws Exception {
    if (!dialingEnabled || session.has("dialAttempt") || pending())
      throw new IllegalStateException("DIAL_BLOCKED");
    refresh();
    JSONObject voice = state.optJSONObject("voice");
    if (!registration.optBoolean("active")
        || !phone.matches("010[0-9]{8}")
        || !phone.endsWith(registration.optString("phoneLast4", "INVALID"))
        || voice == null
        || !voice.optBoolean("ready")
        || !voice.optBoolean("testPhoneInput")
        || voice.optInt("maxSeconds", 999) > 60) throw new IllegalStateException("TRIAL_NOT_READY");
    JSONObject found = null;
    var bookings = state.getJSONArray("bookings");
    for (int i = 0; i < bookings.length(); i++) {
      JSONObject value = bookings.getJSONObject(i);
      if (bookingId.equals(value.optString("id"))) found = value;
    }
    if (found == null
        || !"READY".equals(found.optString("status"))
        || found.optInt("version") != version
        || !found.has("liveTest")
        || found.optBoolean("reviewRequired")) throw new IllegalStateException("BOOKING_CHANGED");
    // Re-register the explicitly entered target under the same owner before dispatch.
    // This cannot replace another owner's active registration.
    register(phone);
    if (!registration.optBoolean("active"))
      throw new IllegalStateException("REGISTRATION_REQUIRED");
    JSONObject request =
        new JSONObject()
            .put("bookingId", bookingId)
            .put("version", version)
            .put("testPhoneNumber", phone)
            .put("requestId", UUID.randomUUID().toString());
    session.put("dialAttempt", request);
    vault.save(session.toString());
    // The durable attempt remains even after timeout/rejection: GET only, never automatic redial.
    wire.exchange("/api/live/dial", request, session);
    refresh();
  }

  boolean dialAttempted() {
    return session.has("dialAttempt");
  }

  void action(String action, JSONObject payload) throws Exception {
    if (!List.of("create_trip", "create_booking", "approve_booking").contains(action))
      throw new IllegalStateException("ACTION_BLOCKED");
    send(
        "/api/action",
        new JSONObject()
            .put("action", action)
            .put("payload", payload)
            .put("requestId", UUID.randomUUID().toString()));
  }

  private void send(String path, JSONObject body) throws Exception {
    if (!connected() || pending()) throw new IllegalStateException("RECOVERY_REQUIRED");
    session.put("pending", new JSONObject().put("path", path).put("body", body));
    vault.save(session.toString());
    retry();
  }

  void retry() throws Exception {
    JSONObject request = session.getJSONObject("pending");
    JSONObject result;
    try {
      result = wire.exchange(request.getString("path"), request.getJSONObject("body"), session);
    } catch (Rejected rejected) {
      JSONObject next = new JSONObject(session.toString());
      next.put("lastRejected", request);
      next.remove("pending");
      vault.save(next.toString());
      session = next;
      throw rejected;
    }
    if (result.has("state")) state = result.getJSONObject("state");
    JSONObject next = new JSONObject(session.toString());
    next.remove("pending");
    vault.save(next.toString());
    session = next;
    refresh();
  }

  static final class Rejected extends Exception {
    Rejected(String code) {
      super(code);
    }
  }

  /** HTTPS only; explicit opt-in dial, no redirects, trust overrides or paid search route. */
  static Wire https(BooleanSupplier allowed) {
    return (path, body, session) -> {
      boolean dial = path.equals("/api/live/dial") && BuildConfig.PHONE_TRIAL_DIAL;
      if (!dial
          && !List.of("/api/state", "/api/action", "/api/live/callback-test-target").contains(path))
        throw new IllegalStateException("PATH_BLOCKED");
      if (!allowed.getAsBoolean()) throw new IllegalStateException("INPUT_BLOCKED");
      HttpURLConnection c =
          (HttpURLConnection)
              new URL("https://43.201.115.170" + path).openConnection(Proxy.NO_PROXY);
      try {
        c.setConnectTimeout(5000);
        c.setReadTimeout(dial ? 45000 : 15000);
        c.setInstanceFollowRedirects(false);
        c.setUseCaches(false);
        c.setRequestProperty("Authorization", session.getString("authorization"));
        c.setRequestProperty("Accept", "application/json");
        if (session.has("cookie")) c.setRequestProperty("Cookie", session.getString("cookie"));
        if (body != null) {
          c.setRequestMethod("POST");
          c.setRequestProperty("X-Prototype-Request", "1");
          c.setRequestProperty("Content-Type", "application/json");
          c.setDoOutput(true);
          byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
          c.setFixedLengthStreamingMode(bytes.length);
          if (!allowed.getAsBoolean()) throw new IllegalStateException("INPUT_BLOCKED");
          try (var out = c.getOutputStream()) {
            out.write(bytes);
          }
        }
        int status = c.getResponseCode();
        if (status != 200) {
          // Only machine codes from an explicit rejected request are exposed. Never echo messages.
          if (status == 400 || status == 403 || status == 404 || status == 409) {
            String code = "";
            try (var in = c.getErrorStream()) {
              if (in != null) {
                java.io.ByteArrayOutputStream error = new java.io.ByteArrayOutputStream();
                int value;
                while (error.size() < 4096 && (value = in.read()) != -1) error.write(value);
                code =
                    new JSONObject(error.toString(StandardCharsets.UTF_8.name()))
                        .getJSONObject("error")
                        .getString("code");
              }
            } catch (Exception ignored) {
              code = "";
            }
            if (code.matches("[A-Z][A-Z_0-9]{1,60}")) throw new Rejected(code);
          }
          throw new IllegalStateException("HTTP_" + status);
        }
        String cookie = c.getHeaderField("Set-Cookie");
        if (cookie != null) {
          String value = cookie.split(";", 2)[0];
          if (!value.matches("phone_(test_session_[0-9]+|session)=[a-f0-9]{64}"))
            throw new IllegalStateException("INVALID_SESSION");
          if (session.has("cookie") && !session.getString("cookie").equals(value))
            throw new IllegalStateException("SESSION_CHANGED");
          session.put("cookie", value);
        }
        try (var in = c.getInputStream()) {
          java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
          byte[] chunk = new byte[8192];
          int count;
          while ((count = in.read(chunk)) != -1) {
            if (buffer.size() + count > 1024 * 1024)
              throw new IllegalStateException("RESPONSE_SIZE");
            buffer.write(chunk, 0, count);
          }
          byte[] bytes = buffer.toByteArray();
          if (bytes.length > 1024 * 1024) throw new IllegalStateException("RESPONSE_SIZE");
          return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        }
      } finally {
        c.disconnect();
      }
    };
  }
}
