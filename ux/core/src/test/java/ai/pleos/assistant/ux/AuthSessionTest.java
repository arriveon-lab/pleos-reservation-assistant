package ai.pleos.assistant.ux;

import static org.junit.jupiter.api.Assertions.*;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;
import org.junit.jupiter.api.*;

class AuthSessionTest {
  static Process server;
  static Path dir;
  static String base;
  static final HttpClient CLIENT =
      HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

  @BeforeEach
  void start() throws Exception {
    dir = Files.createTempDirectory("ux-auth-");
    int port;
    try (ServerSocket socket = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
      port = socket.getLocalPort();
    }
    base = "http://127.0.0.1:" + port + "/api/v1";
    server =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar",
                System.getProperty("mockServerJar"),
                "--server.port=" + port)
            .directory(dir.toFile())
            .redirectErrorStream(true)
            .redirectOutput(dir.resolve("server.log").toFile())
            .start();
    for (int i = 0; i < 150; i++) {
      try {
        raw("/status", null, null, null);
        return;
      } catch (Exception waiting) {
        Thread.sleep(200);
      }
    }
    throw new IllegalStateException("TEST_SERVER_UNAVAILABLE");
  }

  @AfterEach
  void stop() throws Exception {
    if (server != null) {
      server.destroy();
      if (!server.waitFor(10, TimeUnit.SECONDS)) server.destroyForcibly().waitFor();
    }
    if (dir != null)
      try (var paths = Files.walk(dir)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
          Files.deleteIfExists(path);
      }
  }

  static JSONObject raw(String path, JSONObject body, String key, String bearer) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10));
    if (bearer != null) request.header("Authorization", "Bearer " + bearer);
    if (key != null) request.header("Idempotency-Key", key);
    if (body != null)
      request
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
    var response = CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) throw new AuthSession.HttpFailure(response.statusCode());
    return new JSONObject(response.body());
  }

  static class Memory implements AuthSession.Vault {
    String value;
    boolean fail;

    public String read() {
      return value;
    }

    public void save(String next) throws Exception {
      if (fail) throw new java.io.IOException("DISK");
      value = next;
    }
  }

  static class Wire implements AuthSession.Wire {
    int issues, refreshes, writes;
    boolean loseIssue, loseRefresh, rejectOnce, forbidden;
    String refreshKey, previousRefreshKey;
    final List<String> commandKeys = new ArrayList<>();

    public JSONObject exchange(String path, JSONObject body, String key, String bearer)
        throws Exception {
      if (path.equals("/anonymous-sessions")) issues++;
      if (path.endsWith("/refresh")) {
        refreshes++;
        previousRefreshKey = refreshKey;
        refreshKey = key;
      }
      if (path.equals("/searches")) {
        writes++;
        commandKeys.add(key);
      }
      if (bearer != null && forbidden) throw new AuthSession.HttpFailure(403);
      if (bearer != null && rejectOnce) {
        rejectOnce = false;
        throw new AuthSession.HttpFailure(401);
      }
      JSONObject result = raw(path, body, key, bearer);
      if (path.equals("/anonymous-sessions") && loseIssue) {
        loseIssue = false;
        throw new java.io.IOException("LOST");
      }
      if (path.endsWith("/refresh") && loseRefresh) {
        loseRefresh = false;
        throw new java.io.IOException("LOST");
      }
      return result;
    }
  }

  static MockRepository repo(AuthSession auth, MockRepositoryTest.Journal journal)
      throws Exception {
    MockRepository repository = new MockRepository(auth, journal);
    repository.restrict(MockRepository.Restriction.SIMULATED_PARKED);
    return repository;
  }

  @Test
  void explicitIssueSearchRefreshAndProcessRecoveryKeepOwnership() throws Exception {
    Memory vault = new Memory();
    Wire wire = new Wire();
    AuthSession auth = new AuthSession(wire, vault);
    var journal = new MockRepositoryTest.Journal();
    var repository = repo(auth, journal);
    assertThrows(Exception.class, repository::connect);
    assertEquals(0, wire.issues);
    repository.createIdentity();
    repository.search(2, 1, true);
    String id = repository.snapshot().getString("searchId");
    repository.renewIdentity();
    var reopened = repo(new AuthSession(wire, vault), journal);
    reopened.connect();
    assertEquals(id, reopened.snapshot().getJSONObject("search").getString("id"));
    assertEquals(1, wire.issues);
    assertEquals(1, wire.writes);
    assertFalse(journal.saved.contains("Token"));
    assertFalse(reopened.snapshot().toString().contains("pa_"));
  }

  @Test
  void issuanceResponseLossCannotCreateAnotherSubjectAfterRestart() throws Exception {
    Memory vault = new Memory();
    Wire wire = new Wire();
    wire.loseIssue = true;
    AuthSession auth = new AuthSession(wire, vault);
    assertThrows(java.io.IOException.class, auth::createIdentity);
    var restored = new AuthSession(wire, vault);
    assertFalse(restored.canCreate());
    assertThrows(Exception.class, restored::createIdentity);
    assertThrows(Exception.class, restored::ensureSession);
    assertEquals(1, wire.issues);
  }

  @Test
  void refreshResponseLossReusesDurableKeyAndPreservesSearchOwner() throws Exception {
    Memory vault = new Memory();
    Wire wire = new Wire();
    AuthSession auth = new AuthSession(wire, vault);
    var journal = new MockRepositoryTest.Journal();
    var repository = repo(auth, journal);
    repository.createIdentity();
    repository.search(2, 0, false);
    wire.loseRefresh = true;
    assertThrows(java.io.IOException.class, auth::renewIdentity);
    var restored = repo(new AuthSession(wire, vault), journal);
    restored.connect();
    assertEquals(wire.previousRefreshKey, wire.refreshKey);
    assertTrue(restored.snapshot().has("search"));
    assertEquals(1, wire.issues);
    assertEquals(2, wire.refreshes);
  }

  @Test
  void authenticationRejectionRetriesIdenticalCommandOnlyOnce() throws Exception {
    Memory vault = new Memory();
    Wire wire = new Wire();
    var auth = new AuthSession(wire, vault);
    var repository = repo(auth, new MockRepositoryTest.Journal());
    repository.createIdentity();
    wire.rejectOnce = true;
    repository.search(2, 0, false);
    assertEquals(2, wire.writes);
    assertEquals(wire.commandKeys.get(0), wire.commandKeys.get(1));
    assertEquals(1, wire.refreshes);
    assertFalse(repository.snapshot().has("pending"));
  }

  @Test
  void forbiddenDoesNotRefreshOrIssueAnotherIdentity() throws Exception {
    Memory vault = new Memory();
    Wire wire = new Wire();
    var auth = new AuthSession(wire, vault);
    auth.createIdentity();
    wire.forbidden = true;
    assertThrows(
        AuthSession.HttpFailure.class,
        () -> auth.exchange("/searches", new JSONObject(), "test-command-key"));
    assertEquals(0, wire.refreshes);
    assertEquals(1, wire.issues);
    assertThrows(
        IllegalStateException.class,
        () ->
            auth.exchange("/mock/calls/fictional/outcomes", new JSONObject(), "test-command-key"));
  }

  @Test
  void diskFailurePreventsIssueAndRefreshIo() throws Exception {
    Memory vault = new Memory();
    Wire wire = new Wire();
    var auth = new AuthSession(wire, vault);
    vault.fail = true;
    assertThrows(java.io.IOException.class, auth::createIdentity);
    assertEquals(0, wire.issues);
    vault = new Memory();
    auth = new AuthSession(wire, vault);
    auth.createIdentity();
    vault.fail = true;
    assertThrows(java.io.IOException.class, auth::renewIdentity);
    assertEquals(0, wire.refreshes);
  }

  @Test
  void refreshAcknowledgementDiskFailureRetainsOldTokenAndKey() throws Exception {
    Memory vault = new Memory();
    Wire wire = new Wire();
    var initial = new AuthSession(wire, vault);
    initial.createIdentity();
    AuthSession auth =
        new AuthSession(
            (p, b, k, t) -> {
              JSONObject result = wire.exchange(p, b, k, t);
              if (p.endsWith("/refresh")) vault.fail = true;
              return result;
            },
            vault);
    assertThrows(java.io.IOException.class, auth::renewIdentity);
    vault.fail = false;
    var restored = new AuthSession(wire, vault);
    restored.ensureSession();
    assertEquals(wire.previousRefreshKey, wire.refreshKey);
    assertEquals(2, wire.refreshes);
  }

  @Test
  void concurrentRequestsJoinPendingRefresh() throws Exception {
    Memory vault = new Memory();
    Wire wire = new Wire();
    var auth = new AuthSession(wire, vault);
    var repository = repo(auth, new MockRepositoryTest.Journal());
    repository.createIdentity();
    repository.search(2, 0, false);
    String path = "/searches/" + repository.snapshot().getString("searchId");
    wire.rejectOnce = true;
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      var a = pool.submit(() -> auth.exchange(path, null, null));
      var b = pool.submit(() -> auth.exchange(path, null, null));
      a.get();
      b.get();
    } finally {
      pool.shutdownNow();
    }
    assertEquals(1, wire.refreshes);
  }

  @Test
  void revokedSessionBlocksWithoutAnonymousFallback() throws Exception {
    Memory vault = new Memory();
    Wire wire = new Wire();
    var auth = new AuthSession(wire, vault);
    auth.createIdentity();
    String token = new JSONObject(vault.value).getJSONObject("tokens").getString("accessToken");
    var response =
        CLIENT.send(
            HttpRequest.newBuilder(URI.create(base + "/anonymous-sessions/current"))
                .header("Authorization", "Bearer " + token)
                .DELETE()
                .build(),
            HttpResponse.BodyHandlers.discarding());
    assertEquals(200, response.statusCode());
    assertThrows(AuthSession.HttpFailure.class, auth::renewIdentity);
    var restored = new AuthSession(wire, vault);
    assertThrows(Exception.class, restored::ensureSession);
    assertFalse(restored.canCreate());
    assertEquals(1, wire.issues);
  }

  @Test
  void legacyOrMismatchedRecoveryCannotAcquireNewOwner() throws Exception {
    var journal = new MockRepositoryTest.Journal();
    journal.saved =
        new JSONObject()
            .put("tripId", UUID.randomUUID().toString())
            .put("searchId", UUID.randomUUID().toString())
            .toString();
    Wire wire = new Wire();
    var auth = new AuthSession(wire, new Memory());
    var repository = repo(auth, journal);
    assertFalse(repository.canCreateIdentity());
    assertThrows(Exception.class, repository::createIdentity);
    assertEquals(0, wire.issues);
    auth.createIdentity();
    assertThrows(Exception.class, repository::connect);
    journal.saved =
        new JSONObject()
            .put("tripId", UUID.randomUUID().toString())
            .put("authBinding", "another-installation")
            .toString();
    var mismatch = repo(auth, journal);
    assertThrows(Exception.class, mismatch::connect);
  }

  @Test
  void refreshReplayWindowConflictRemainsBlockedAcrossRestart() throws Exception {
    Memory vault = new Memory();
    Wire wire = new Wire();
    AuthSession initial = new AuthSession(wire, vault);
    initial.createIdentity();
    AuthSession expiredReplay =
        new AuthSession(
            (p, b, k, t) -> {
              if (p.endsWith("/refresh")) throw new AuthSession.HttpFailure(409);
              return wire.exchange(p, b, k, t);
            },
            vault);
    assertThrows(AuthSession.HttpFailure.class, expiredReplay::renewIdentity);
    AuthSession restored = new AuthSession(wire, vault);
    assertThrows(Exception.class, restored::ensureSession);
    assertThrows(Exception.class, restored::createIdentity);
    assertEquals(1, wire.issues);
  }

  @Test
  void corruptVaultAndUnknownDrivingFailClosed() throws Exception {
    Memory vault = new Memory();
    vault.value = "{}";
    assertThrows(Exception.class, () -> new AuthSession(new Wire(), vault));
    Wire wire = new Wire();
    var repository =
        new MockRepository(new AuthSession(wire, new Memory()), new MockRepositoryTest.Journal());
    assertThrows(Exception.class, repository::createIdentity);
    assertEquals(0, wire.issues);
  }
}
