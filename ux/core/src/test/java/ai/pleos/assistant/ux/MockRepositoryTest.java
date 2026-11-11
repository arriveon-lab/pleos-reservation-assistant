package ai.pleos.assistant.ux;

import static org.junit.jupiter.api.Assertions.*;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MockRepositoryTest {
  static Process server;
  static String base;
  static Path temporary;
  static HttpClient client =
      HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

  @BeforeAll
  static void start() throws Exception {
    temporary = Files.createTempDirectory("ux-mock-");
    int port;
    try (ServerSocket socket = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
      port = socket.getLocalPort();
    }
    base = "http://127.0.0.1:" + port + "/api/v1";
    server =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                Files.readString(Path.of(System.getProperty("smokeClasspathFile"))),
                "ai.pleos.assistant.OwnershipSmokeApplication",
                "--app.mode=MOCK",
                "--server.address=127.0.0.1",
                "--server.port=" + port)
            .directory(temporary.toFile())
            .redirectErrorStream(true)
            .redirectOutput(temporary.resolve("server.log").toFile())
            .start();
    for (int i = 0; i < 150; i++) {
      try {
        http("/status", null, null);
        return;
      } catch (Exception waiting) {
        Thread.sleep(200);
      }
    }
    server.destroyForcibly();
    fail("MOCK server failed to start; see " + temporary.resolve("server.log"));
  }

  @AfterAll
  static void stop() throws Exception {
    if (server != null) {
      server.destroy();
      if (!server.waitFor(10, java.util.concurrent.TimeUnit.SECONDS))
        server.destroyForcibly().waitFor();
    }
    if (temporary != null)
      try (var paths = Files.walk(temporary)) {
        for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList())
          Files.deleteIfExists(path);
      }
  }

  static JSONObject http(String path, JSONObject body, String key) throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10));
    if (body != null)
      builder
          .header("Content-Type", "application/json")
          .header("Idempotency-Key", key)
          .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
    HttpResponse<String> response =
        client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200)
      throw new IllegalStateException("HTTP_" + response.statusCode());
    return new JSONObject(response.body());
  }

  static class Journal implements MockRepository.Journal {
    String saved;
    boolean fail;

    public String read() {
      return saved;
    }

    public void save(String value) {
      if (fail) throw new IllegalStateException("DISK_FAILED");
      saved = value;
    }
  }

  static class Recording implements MockRepository.Transport {
    List<String> writes = new ArrayList<>();
    boolean loseResponse;
    Journal journal;

    Recording(Journal journal) {
      this.journal = journal;
    }

    public JSONObject exchange(String path, JSONObject body, String key) throws Exception {
      if (body != null) {
        assertNotNull(new JSONObject(journal.saved).getJSONObject("pending"));
        writes.add(path + "|" + key + "|" + body);
      }
      JSONObject result = http(path, body, key);
      if (body != null && loseResponse) {
        loseResponse = false;
        throw new java.io.IOException("LOST_RESPONSE");
      }
      return result;
    }
  }

  MockRepository ready(Journal journal, Recording transport) throws Exception {
    MockRepository repository = new MockRepository(transport, journal);
    repository.connect();
    repository.restrict(MockRepository.Restriction.SIMULATED_PARKED);
    return repository;
  }

  MockRepository searchable() throws Exception {
    Journal journal = new Journal();
    MockRepository repository = ready(journal, new Recording(journal));
    repository.search(2, 1, true);
    return repository;
  }

  void prepareBooking(MockRepository repository) throws Exception {
    assertFalse(repository.candidate("mock-1").getBoolean("bookable"));
    repository.inquire("mock-1");
    repository.simulate("ANSWERED");
    assertTrue(repository.candidate("mock-1").getBoolean("bookable"));
  }

  @Test
  void completeMockFlowPreservesConfirmedTimeDuringUnknownChange() throws Exception {
    MockRepository repository = searchable();
    assertEquals(
        3, repository.snapshot().getJSONObject("search").getJSONArray("candidates").length());
    prepareBooking(repository);
    repository.book("mock-1", true);
    assertEquals("PENDING", repository.snapshot().getJSONObject("reservation").getString("status"));
    assertTrue(repository.snapshot().getJSONObject("reservation").isNull("confirmedTime"));
    repository.simulate("ACCEPTED");
    String confirmed =
        repository.snapshot().getJSONObject("reservation").getString("confirmedTime");
    repository.change(true);
    assertEquals(
        confirmed, repository.snapshot().getJSONObject("reservation").getString("confirmedTime"));
    repository.simulate("UNKNOWN");
    JSONObject reservation = repository.snapshot().getJSONObject("reservation");
    assertEquals("CONFIRMED", reservation.getString("status"));
    assertEquals(confirmed, reservation.getString("confirmedTime"));
    assertFalse(reservation.isNull("pendingChangeCallId"));
    assertThrows(IllegalStateException.class, () -> repository.change(true));
  }

  @ParameterizedTest
  @ValueSource(strings = {"UNKNOWN", "REJECTED"})
  void unsuccessfulBookingCannotBecomeConfirmedOrIssueAnotherBooking(String outcome)
      throws Exception {
    MockRepository repository = searchable();
    prepareBooking(repository);
    repository.book("mock-1", true);
    repository.simulate(outcome);
    assertEquals(outcome, repository.snapshot().getJSONObject("reservation").getString("status"));
    assertTrue(repository.snapshot().getJSONObject("reservation").isNull("confirmedTime"));
    assertThrows(IllegalStateException.class, () -> repository.book("mock-1", true));
  }

  @ParameterizedTest
  @ValueSource(strings = {"ACCEPTED", "REJECTED", "UNKNOWN"})
  void changeReceiptSurvivesRefreshAndRestart(String outcome) throws Exception {
    Journal journal = new Journal();
    Recording wire = new Recording(journal);
    MockRepository repository = ready(journal, wire);
    repository.search(2, 1, true);
    prepareBooking(repository);
    repository.book("mock-1", true);
    repository.simulate("ACCEPTED");
    String original = repository.snapshot().getJSONObject("reservation").getString("confirmedTime");
    repository.change(true);
    JSONObject change = repository.snapshot().getJSONObject("call");
    repository.simulate(outcome);
    repository.refresh();
    MockRepository reopened = ready(journal, wire);
    JSONObject state = reopened.snapshot();
    assertEquals(change.getString("id"), state.getJSONObject("call").getString("id"));
    assertEquals("CHANGE", state.getJSONObject("call").getString("purpose"));
    assertEquals(outcome, state.getJSONObject("call").getString("status"));
    JSONObject reservation = state.getJSONObject("reservation");
    assertEquals(original, reservation.getString("originalRequestedTime"));
    assertEquals(
        "ACCEPTED".equals(outcome) ? change.getString("requestedTime") : original,
        reservation.getString("confirmedTime"));
    assertEquals(0, reservation.getInt("totalAdditionalCostKrw"));
    assertEquals(!"UNKNOWN".equals(outcome), reservation.isNull("pendingChangeCallId"));
    int writes = wire.writes.size();
    assertThrows(IllegalStateException.class, () -> reopened.change(true));
    assertEquals(writes, wire.writes.size());
  }

  @ParameterizedTest
  @ValueSource(strings = {"booking-time", "booking-cost", "change-time", "change-cost"})
  void mismatchedAcceptanceStaysUnknown(String scenario) throws Exception {
    MockRepository repository = searchable();
    prepareBooking(repository);
    repository.book("mock-1", true);
    boolean change = scenario.startsWith("change");
    if (change) {
      repository.simulate("ACCEPTED");
      repository.change(true);
    }
    JSONObject call = repository.snapshot().getJSONObject("call");
    JSONObject event =
        new JSONObject()
            .put("eventId", java.util.UUID.randomUUID().toString())
            .put("outcome", "ACCEPTED")
            .put("observedAt", java.time.Instant.now().toString())
            .put("explicitAcceptance", true)
            .put(
                "acceptedTime",
                scenario.endsWith("time")
                    ? java.time.Instant.parse(call.getString("requestedTime"))
                        .plusSeconds(60)
                        .toString()
                    : call.getString("requestedTime"))
            .put("additionalCostKrw", scenario.endsWith("cost") ? 100 : 0);
    String path = "/mock/calls/" + call.getString("id") + "/outcomes";
    JSONObject first = http(path, event, "fixture");
    assertTrue(first.similar(http(path, event, "fixture")));
    repository.refresh();
    assertEquals("UNKNOWN", repository.snapshot().getJSONObject("call").getString("status"));
    JSONObject reservation = repository.snapshot().getJSONObject("reservation");
    assertEquals(change ? "CONFIRMED" : "UNKNOWN", reservation.getString("status"));
    if (change) {
      assertEquals(
          reservation.getString("originalRequestedTime"), reservation.getString("confirmedTime"));
      assertFalse(reservation.isNull("pendingChangeCallId"));
    } else assertTrue(reservation.isNull("confirmedTime"));
  }

  @Test
  void explicitApprovalAndBookableAreRequired() throws Exception {
    MockRepository repository = searchable();
    assertThrows(IllegalStateException.class, () -> repository.book("mock-1", true));
    prepareBooking(repository);
    assertThrows(IllegalStateException.class, () -> repository.book("mock-1", false));
    assertFalse(repository.snapshot().has("reservationId"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"UNKNOWN", "RESTRICTED"})
  void unknownAndRestrictedBlockCommandsButPermitReads(String value) throws Exception {
    Journal journal = new Journal();
    Recording transport = new Recording(journal);
    MockRepository repository = ready(journal, transport);
    repository.restrict(MockRepository.Restriction.valueOf(value));
    assertThrows(IllegalStateException.class, () -> repository.search(2, 1, true));
    repository.refresh();
    assertTrue(transport.writes.isEmpty());
  }

  @Test
  void lostBookingResponseSurvivesProcessRecreationWithIdenticalKeyAndBody() throws Exception {
    Journal journal = new Journal();
    Recording transport = new Recording(journal);
    MockRepository repository = ready(journal, transport);
    repository.search(2, 1, true);
    prepareBooking(repository);
    transport.loseResponse = true;
    assertThrows(java.io.IOException.class, () -> repository.book("mock-1", true));
    String intent = transport.writes.get(transport.writes.size() - 1);
    MockRepository restored = new MockRepository(transport, journal);
    restored.connect();
    assertEquals(MockRepository.Restriction.UNKNOWN, restored.restriction());
    assertThrows(IllegalStateException.class, restored::retry);
    restored.restrict(MockRepository.Restriction.SIMULATED_PARKED);
    assertThrows(IllegalStateException.class, () -> restored.search(2, 0, false));
    restored.retry();
    assertEquals(intent, transport.writes.get(transport.writes.size() - 1));
    String id = restored.snapshot().getString("reservationId");
    int commands = transport.writes.size();
    MockRepository reopened = new MockRepository(transport, journal);
    reopened.connect();
    assertEquals(id, reopened.snapshot().getJSONObject("reservation").getString("id"));
    assertEquals(commands, transport.writes.size());
    assertFalse(new JSONObject(journal.saved).has("search"));
  }

  @Test
  void restrictionDuringJournalWriteKeepsPendingWithoutDispatch() throws Exception {
    MockRepository[] reference = new MockRepository[1];
    Journal journal =
        new Journal() {
          @Override
          public void save(String value) {
            super.save(value);
            if (reference[0] != null) reference[0].restrict(MockRepository.Restriction.RESTRICTED);
          }
        };
    Recording transport = new Recording(journal);
    MockRepository repository = ready(journal, transport);
    reference[0] = repository;
    repository.restrict(MockRepository.Restriction.PLATFORM_UNRESTRICTED);
    assertThrows(IllegalStateException.class, () -> repository.search(2, 1, true));
    assertTrue(transport.writes.isEmpty());
    assertTrue(repository.snapshot().has("pending"));
    assertThrows(IllegalStateException.class, repository::retry);
    repository.refresh();
    assertTrue(transport.writes.isEmpty());
  }

  @Test
  void restrictionDuringStatusReadPreventsNewIdentity() throws Exception {
    MockRepository[] reference = new MockRepository[1];
    boolean[] created = {false};
    AuthSession auth =
        new AuthSession(
            (path, body, key, bearer) -> {
              if (body != null) created[0] = true;
              reference[0].restrict(MockRepository.Restriction.UNKNOWN);
              return new JSONObject()
                  .put("mode", "MOCK")
                  .put("contractVersion", "1.1.0")
                  .put("externalCallsEnabled", false);
            },
            new AuthSession.Vault() {
              public String read() {
                return null;
              }

              public void save(String value) {}
            });
    MockRepository repository = new MockRepository(auth, new Journal());
    assertTrue(repository.canCreateIdentity());
    reference[0] = repository;
    repository.restrict(MockRepository.Restriction.PLATFORM_UNRESTRICTED);
    assertThrows(IllegalStateException.class, repository::createIdentity);
    assertFalse(created[0]);
  }

  @Test
  void diskFailurePreventsSending() throws Exception {
    Journal journal = new Journal();
    Recording transport = new Recording(journal);
    MockRepository repository = ready(journal, transport);
    journal.fail = true;
    assertThrows(IllegalStateException.class, () -> repository.search(2, 1, true));
    assertTrue(transport.writes.isEmpty());
    assertThrows(IllegalStateException.class, repository::retry);
    assertTrue(transport.writes.isEmpty());
  }

  @Test
  void repeatedClickAfterSuccessfulBookingDoesNotPostAgain() throws Exception {
    Journal journal = new Journal();
    Recording transport = new Recording(journal);
    MockRepository repository = ready(journal, transport);
    repository.search(2, 1, true);
    prepareBooking(repository);
    repository.book("mock-1", true);
    int count = transport.writes.size();
    assertThrows(IllegalStateException.class, () -> repository.book("mock-1", true));
    assertEquals(count, transport.writes.size());
  }

  @Test
  void acknowledgementDiskFailureKeepsOriginalIntentBlockedUntilSaved() throws Exception {
    Journal journal = new Journal();
    Recording transport = new Recording(journal);
    MockRepository repository =
        new MockRepository(
            (path, body, key) -> {
              JSONObject result = transport.exchange(path, body, key);
              if (body != null) journal.fail = true;
              return result;
            },
            journal);
    repository.connect();
    repository.restrict(MockRepository.Restriction.SIMULATED_PARKED);
    assertThrows(IllegalStateException.class, () -> repository.search(2, 1, true));
    assertTrue(repository.snapshot().has("pending"));
    assertThrows(IllegalStateException.class, () -> repository.search(3, 0, false));
    String intent = transport.writes.get(0);
    journal.fail = false;
    MockRepository restored = ready(journal, transport);
    restored.retry();
    assertEquals(intent, transport.writes.get(1));
    assertFalse(restored.snapshot().has("pending"));
  }

  @Test
  void unknownInquiryCannotBeRedialed() throws Exception {
    MockRepository repository = searchable();
    repository.inquire("mock-1");
    repository.simulate("UNKNOWN");
    assertThrows(IllegalStateException.class, () -> repository.inquire("mock-1"));
    assertThrows(IllegalStateException.class, () -> repository.search(3, 0, false));
  }

  @Test
  void newSearchClearsFinishedCallButKeepsTripAndUpdatedParty() throws Exception {
    MockRepository repository = searchable();
    String trip = repository.snapshot().getString("tripId");
    repository.inquire("mock-1");
    assertThrows(IllegalStateException.class, () -> repository.search(3, 0, false));
    repository.simulate("ANSWERED");
    repository.search(3, 0, false);
    JSONObject state = repository.snapshot();
    assertEquals(trip, state.getString("tripId"));
    assertFalse(state.has("call"));
    assertFalse(state.has("callId"));
    assertEquals(
        3,
        state
            .getJSONObject("search")
            .getJSONObject("request")
            .getJSONObject("party")
            .getInt("adults"));
  }

  @Test
  void incompatibleContractAndLiveModeBlockCommands() throws Exception {
    for (JSONObject status :
        List.of(
            new JSONObject()
                .put("mode", "LIVE")
                .put("externalCallsEnabled", true)
                .put("contractVersion", "1.0.0"),
            new JSONObject()
                .put("mode", "MOCK")
                .put("externalCallsEnabled", false)
                .put("contractVersion", "1.0.0"))) {
      MockRepository repository = new MockRepository((p, b, k) -> status, new Journal());
      assertThrows(IllegalStateException.class, repository::connect);
      repository.restrict(MockRepository.Restriction.SIMULATED_PARKED);
      assertThrows(IllegalStateException.class, () -> repository.search(2, 1, true));
    }
  }

  @Test
  void corruptRecoveryDoesNotSilentlyCreateNewIdentity() {
    Journal journal = new Journal();
    journal.saved = "{}";
    assertThrows(Exception.class, () -> new MockRepository(MockRepositoryTest::http, journal));
  }
}
