package ai.pleos.assistant.ux;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class PreviewAuthTest {
  static JSONObject tokens(int n) {
    return new JSONObject()
        .put("mode", "MOCK")
        .put("tokenType", "Bearer")
        .put("accessToken", "pa_" + String.valueOf(n).repeat(43))
        .put("refreshToken", "pr_" + String.valueOf(n).repeat(43))
        .put("accessTokenExpiresAt", "2030-01-01T00:00:00Z")
        .put("refreshTokenExpiresAt", "2030-02-01T00:00:00Z");
  }

  static AuthSession.Vault vault() {
    return new AuthSession.Vault() {
      String saved;

      public String read() {
        return saved;
      }

      public void save(String value) {
        saved = value;
      }
    };
  }

  @Test
  void cancelDoesNotWaitForLongQueryUnderAuthenticationLock() throws Exception {
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    AuthSession auth =
        new AuthSession(
            (path, body, key, bearer) -> {
              if (path.equals("/anonymous-sessions")) return tokens(1);
              if (path.endsWith("/queries")) {
                entered.countDown();
                assertTrue(release.await(3, TimeUnit.SECONDS));
              }
              return new JSONObject();
            },
            vault());
    auth.createIdentity();
    var workers = Executors.newFixedThreadPool(2);
    try {
      Future<?> query =
          workers.submit(
              () -> {
                try {
                  auth.exchangeTransient(
                      PreviewController.ROOT + "/id/queries", new JSONObject(), null);
                } catch (Exception e) {
                  throw new RuntimeException(e);
                }
              });
      assertTrue(entered.await(1, TimeUnit.SECONDS));
      Future<?> cancel =
          workers.submit(
              () -> {
                try {
                  auth.exchangeTransient(
                      PreviewController.ROOT + "/id/cancel", new JSONObject(), null);
                } catch (Exception e) {
                  throw new RuntimeException(e);
                }
              });
      try {
        cancel.get(1, TimeUnit.SECONDS);
      } finally {
        release.countDown();
      }
      query.get(1, TimeUnit.SECONDS);
    } finally {
      workers.shutdownNow();
    }
  }

  @Test
  void concurrentRejectionsRefreshOnceAndRetrySameBodies() throws Exception {
    CountDownLatch rejected = new CountDownLatch(2);
    AtomicInteger refresh = new AtomicInteger(), success = new AtomicInteger();
    AuthSession auth =
        new AuthSession(
            (path, body, key, bearer) -> {
              if (path.equals("/anonymous-sessions")) return tokens(1);
              if (path.endsWith("/refresh")) {
                refresh.incrementAndGet();
                return tokens(2);
              }
              if (bearer.equals(tokens(1).getString("accessToken"))) {
                rejected.countDown();
                assertTrue(rejected.await(2, TimeUnit.SECONDS));
                throw new AuthSession.HttpFailure(401);
              }
              assertEquals(7, body.getInt("generation"));
              success.incrementAndGet();
              return new JSONObject();
            },
            vault());
    auth.createIdentity();
    String binding = auth.binding();
    var workers = Executors.newFixedThreadPool(2);
    try {
      Callable<Void> call =
          () -> {
            auth.exchangeTransient(
                PreviewController.ROOT + "/id/cancel", new JSONObject().put("generation", 7), null);
            return null;
          };
      var a = workers.submit(call);
      var b = workers.submit(call);
      a.get(3, TimeUnit.SECONDS);
      b.get(3, TimeUnit.SECONDS);
    } finally {
      workers.shutdownNow();
    }
    assertEquals(1, refresh.get());
    assertEquals(2, success.get());
    assertEquals(binding, auth.binding());
  }

  @Test
  void networkLossIsNotAutomaticallyRetried() throws Exception {
    AtomicInteger sent = new AtomicInteger();
    AuthSession auth =
        new AuthSession(
            (path, body, key, bearer) -> {
              if (path.equals("/anonymous-sessions")) return tokens(1);
              sent.incrementAndGet();
              throw new java.io.IOException();
            },
            vault());
    auth.createIdentity();
    assertThrows(
        java.io.IOException.class,
        () -> auth.exchangeTransient(PreviewController.ROOT, new JSONObject(), null));
    assertEquals(1, sent.get());
    assertThrows(
        IllegalArgumentException.class,
        () -> auth.exchangeTransient(PreviewController.ROOT + "-other", new JSONObject(), null));
  }
}
