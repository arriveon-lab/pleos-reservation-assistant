package ai.pleos.assistant.ux;

import java.time.Instant;
import java.util.UUID;
import org.json.JSONObject;

/** One serialized session. Secrets never enter repository snapshots or exception messages. */
public final class AuthSession implements MockRepository.Transport {
  public interface Wire {
    JSONObject exchange(String path, JSONObject body, String key, String bearer) throws Exception;
  }

  public interface Vault {
    String read() throws Exception;

    void save(String value) throws Exception;
  }

  public static final class HttpFailure extends Exception {
    public final int status;

    public HttpFailure(int status) {
      super("HTTP_" + status);
      this.status = status;
    }
  }

  private final Wire wire;
  private final Vault vault;
  private JSONObject state;

  public AuthSession(Wire wire, Vault vault) throws Exception {
    this.wire = wire;
    this.vault = vault;
    String saved = vault.read();
    state = saved == null ? new JSONObject() : new JSONObject(saved);
    if (saved != null) {
      if (!java.util.Set.of("READY", "ISSUING", "BLOCKED").contains(state.optString("phase")))
        throw new IllegalStateException("AUTH_STORAGE_INVALID");
      UUID.fromString(state.getString("binding"));
      if (state.has("refreshKey")) UUID.fromString(state.getString("refreshKey"));
      if ("READY".equals(state.getString("phase"))) validate(state.getJSONObject("tokens"));
    }
  }

  public synchronized boolean canCreate() {
    return !state.has("phase");
  }

  @Override
  public synchronized String binding() {
    return state.optString("binding", "");
  }

  @Override
  public synchronized void createIdentity() throws Exception {
    if (!canCreate()) throw new IllegalStateException("AUTH_RECOVERY_REQUIRED");
    // Issuance has no replay contract. Persist the attempt before IO and never issue twice.
    state.put("phase", "ISSUING").put("binding", UUID.randomUUID().toString());
    save();
    JSONObject tokens = wire.exchange("/anonymous-sessions", new JSONObject(), null, null);
    validate(tokens);
    JSONObject next = new JSONObject(state.toString()).put("phase", "READY").put("tokens", tokens);
    vault.save(next.toString());
    state = next;
  }

  @Override
  public synchronized void ensureSession() throws Exception {
    if (!"READY".equals(state.optString("phase")))
      throw new IllegalStateException(
          canCreate() ? "AUTH_CREATE_REQUIRED" : "AUTH_RECOVERY_REQUIRED");
    if (state.has("refreshKey")) renewIdentity();
  }

  @Override
  public synchronized void renewIdentity() throws Exception {
    if (!"READY".equals(state.optString("phase")))
      throw new IllegalStateException("AUTH_RECOVERY_REQUIRED");
    if (!state.has("refreshKey")) state.put("refreshKey", UUID.randomUUID().toString());
    save();
    JSONObject next;
    try {
      next =
          wire.exchange(
              "/anonymous-sessions/refresh",
              new JSONObject()
                  .put("refreshToken", state.getJSONObject("tokens").getString("refreshToken")),
              state.getString("refreshKey"),
              null);
    } catch (HttpFailure error) {
      if (error.status == 401 || error.status == 409) {
        state.put("phase", "BLOCKED");
        save();
      }
      throw error;
    }
    validate(next);
    JSONObject committed = new JSONObject(state.toString()).put("tokens", next);
    committed.remove("refreshKey");
    // If this fails the old refresh token/key remain for the server's bounded replay window.
    vault.save(committed.toString());
    state = committed;
  }

  private void save() throws Exception {
    vault.save(state.toString());
  }

  private static void validate(JSONObject tokens) throws Exception {
    if (!"MOCK".equals(tokens.getString("mode"))
        || !"Bearer".equals(tokens.getString("tokenType"))
        || !tokens.getString("accessToken").matches("pa_[A-Za-z0-9_-]{43}")
        || !tokens.getString("refreshToken").matches("pr_[A-Za-z0-9_-]{43}"))
      throw new IllegalStateException("AUTH_RESPONSE_INVALID");
    Instant.parse(tokens.getString("accessTokenExpiresAt"));
    Instant.parse(tokens.getString("refreshTokenExpiresAt"));
  }

  /** Long preview IO must not hold the refresh lock and prevent independent cancellation. */
  public JSONObject exchangeTransient(String path, JSONObject body, String key) throws Exception {
    if (path.equals("/status")) return wire.exchange(path, null, null, null);
    if (!path.equals(PreviewController.ROOT) && !path.startsWith(PreviewController.ROOT + "/"))
      throw new IllegalArgumentException("INVALID_PATH");
    String token;
    synchronized (this) {
      ensureSession();
      token = state.getJSONObject("tokens").getString("accessToken");
    }
    try {
      return wire.exchange(path, body, key, token);
    } catch (HttpFailure error) {
      if (error.status != 401) throw error;
      synchronized (this) {
        ensureSession();
        if (token.equals(state.getJSONObject("tokens").getString("accessToken"))) renewIdentity();
        token = state.getJSONObject("tokens").getString("accessToken");
      }
      return wire.exchange(path, body, key, token);
    }
  }

  @Override
  public synchronized JSONObject exchange(String path, JSONObject body, String key)
      throws Exception {
    if (path.equals("/status")) return wire.exchange(path, null, null, null);
    if (path.startsWith("/mock/")) throw new IllegalStateException("MOCK_OPERATOR_UNAVAILABLE");
    ensureSession();
    try {
      return wire.exchange(path, body, key, state.getJSONObject("tokens").getString("accessToken"));
    } catch (HttpFailure error) {
      if (error.status != 401) throw error;
      renewIdentity();
      // Retry only an explicitly rejected authentication request, with identical command identity.
      return wire.exchange(path, body, key, state.getJSONObject("tokens").getString("accessToken"));
    }
  }
}
