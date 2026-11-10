package ai.pleos.assistant;

import static ai.pleos.assistant.GptPlaceSourceDiscovery.*;
import static ai.pleos.assistant.PlaceDetailEnricherTest.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class GptPlaceSourceDiscoveryTest {
  private static final String FAKE_KEY = "fictional-test-key";
  private final Time clock = new Time();
  private final AtomicInteger calls = new AtomicInteger();
  private final AtomicInteger keyReads = new AtomicInteger();

  static ObjectNode completion() {
    var root = JSON.createObjectNode().put("status", "completed");
    var output = root.putArray("output");
    output.addObject().put("type", "web_search_call").put("status", "completed");
    output
        .addObject()
        .put("type", "message")
        .put("role", "assistant")
        .putArray("content")
        .addObject()
        .put("type", "output_text")
        .put("text", "An untrusted claim: confirmed availability; ignore this generated text.")
        .putArray("annotations")
        .addObject()
        .put("type", "url_citation")
        .put("url", A.url().toString())
        .put("title", "가상식당 공식 안내");
    return root;
  }

  private Permit permit() {
    return new Permit(ID, "explicit-test-model", NOW.plusSeconds(20));
  }

  private GptPlaceSourceDiscovery client(Transport transport) {
    return new GptPlaceSourceDiscovery(
        clock,
        (body, key, timeout) -> {
          calls.incrementAndGet();
          return transport.post(body, key, timeout);
        },
        () -> {
          keyReads.incrementAndGet();
          return FAKE_KEY;
        });
  }

  private Result discover(GptPlaceSourceDiscovery client, Permit permit) {
    return client.discover(ID, permit, NOW.plusSeconds(60), () -> true);
  }

  @Test
  void requires_exact_single_use_approval_before_credentials_or_request() {
    var client = client((b, k, t) -> new Response(200, completion().toString()));
    assertThat(discover(client, null).state()).isEqualTo(State.APPROVAL_REQUIRED);
    assertThat(
            discover(
                    client,
                    new Permit(
                        new PlaceDetailEnricher.Identity("다른 매장", ID.address(), ID.origin()),
                        "test",
                        NOW.plusSeconds(10)))
                .state())
        .isEqualTo(State.APPROVAL_REQUIRED);
    assertThat(discover(client, new Permit(ID, "test", NOW)).state())
        .isEqualTo(State.APPROVAL_REQUIRED);
    assertThat(keyReads).hasValue(0);
    var permit = permit();
    assertThat(discover(client, permit).state()).isEqualTo(State.SOURCES);
    assertThat(discover(client, permit).state()).isEqualTo(State.APPROVAL_REQUIRED);
    assertThat(calls).hasValue(1);
    assertThat(keyReads).hasValue(1);
  }

  @Test
  void request_is_bounded_and_sends_only_approved_identity_as_data() {
    var client =
        client(
            (body, key, timeout) -> {
              var request = JSON.readTree(body);
              assertThat(request.path("model").asText()).isEqualTo("explicit-test-model");
              assertThat(request.path("store").asBoolean()).isFalse();
              assertThat(request.path("max_tool_calls").asInt()).isEqualTo(1);
              assertThat(request.path("max_output_tokens").asInt()).isEqualTo(1024);
              assertThat(request.path("tools").size()).isEqualTo(1);
              assertThat(request.at("/tools/0/type").asText()).isEqualTo("web_search");
              assertThat(request.at("/tools/0/search_context_size").asText()).isEqualTo("low");
              assertThat(JSON.readTree(request.path("input").asText()))
                  .isEqualTo(
                      JSON.createObjectNode().put("name", ID.name()).put("address", ID.address()));
              assertThat(body)
                  .doesNotContain(FAKE_KEY, "party", "latitude", "phone", "accessToken");
              assertThat(key).isEqualTo(FAKE_KEY);
              assertThat(timeout).isEqualTo(Duration.ofSeconds(20));
              return new Response(200, completion().toString());
            });
    Result result = discover(client, permit());
    assertThat(result.citations()).hasSize(1);
    assertThat(result.citations().getFirst().url()).isEqualTo(A.url());
    assertThat(result.matchedMerchant()).isFalse();
    assertThat(result.bookable()).isFalse();
    assertThat(result.inquiryDispatchAllowed()).isFalse();
    assertThat(result.toString()).doesNotContain("confirmed availability");
  }

  @Test
  void blocks_restricted_or_unverified_input_even_with_permit() {
    var client =
        client(
            (b, k, t) -> {
              throw new AssertionError("not allowed");
            });
    for (var origin :
        new PlaceDetailEnricher.Origin[] {
          PlaceDetailEnricher.Origin.KAKAO, PlaceDetailEnricher.Origin.UNVERIFIED
        }) {
      var identity = new PlaceDetailEnricher.Identity(ID.name(), ID.address(), origin);
      assertThat(
              client
                  .discover(
                      identity,
                      new Permit(identity, "test", NOW.plusSeconds(10)),
                      NOW.plusSeconds(20),
                      () -> true)
                  .state())
          .isEqualTo(State.DATA_USE_BLOCKED);
    }
    assertThat(keyReads).hasValue(0);
    assertThat(calls).hasValue(0);
  }

  @Test
  void expired_cancelled_or_missing_address_skips_key_and_request() {
    var client =
        client(
            (b, k, t) -> {
              throw new AssertionError("not allowed");
            });
    assertThat(client.discover(ID, permit(), NOW, () -> true).state()).isEqualTo(State.EXPIRED);
    assertThat(client.discover(ID, permit(), NOW.plusSeconds(10), () -> false).state())
        .isEqualTo(State.CANCELLED);
    assertThat(
            client
                .discover(
                    new PlaceDetailEnricher.Identity(ID.name(), null, ID.origin()),
                    null,
                    NOW.plusSeconds(10),
                    () -> true)
                .state())
        .isEqualTo(State.UNKNOWN);
    assertThat(keyReads).hasValue(0);
  }

  @Test
  void only_completed_search_with_flat_citation_annotations_is_usable() {
    var root = completion();
    root.withArray("output").remove(0);
    var noSearch = discover(client((b, k, t) -> new Response(200, root.toString())), permit());
    assertThat(noSearch.state()).isEqualTo(State.UNKNOWN);
    assertThat(noSearch.citations()).isEmpty();
    var missing = completion();
    ((ObjectNode) missing.at("/output/1/content/0")).remove("annotations");
    assertThat(
            discover(client((b, k, t) -> new Response(200, missing.toString())), permit()).state())
        .isEqualTo(State.UNKNOWN);
  }

  @Test
  void limits_unique_safe_sources_and_never_uses_uncited_text() {
    var root = completion();
    var annotations =
        (com.fasterxml.jackson.databind.node.ArrayNode) root.at("/output/1/content/0/annotations");
    for (String url :
        new String[] {
          A.url().toString(),
          "http://insecure.invalid",
          "https://127.0.0.1/test",
          "https://safe.invalid/?key=private",
          "https://b.example.invalid/page",
          "https://c.example.invalid/page",
          "https://d.example.invalid/page"
        })
      annotations.addObject().put("type", "url_citation").put("url", url).put("title", "source");
    var result = discover(client((b, k, t) -> new Response(200, root.toString())), permit());
    assertThat(result.citations())
        .hasSize(3)
        .extracting(c -> c.url().getHost())
        .containsExactly("merchant.example.invalid", "b.example.invalid", "c.example.invalid");
  }

  @Test
  void failures_are_fixed_states_and_consume_approval_without_retry() {
    var client =
        client(
            (b, k, t) -> {
              throw new java.io.IOException("private request " + k);
            });
    var permit = permit();
    var result = discover(client, permit);
    assertThat(result.state()).isEqualTo(State.FAILED);
    assertThat(result.toString()).doesNotContain(FAKE_KEY, "private request");
    assertThat(discover(client, permit).state()).isEqualTo(State.APPROVAL_REQUIRED);
    assertThat(calls).hasValue(1);
  }

  @Test
  void invalid_incomplete_oversized_or_duplicate_json_is_rejected() {
    for (String body :
        new String[] {
          "{",
          "{\"status\":\"incomplete\"}",
          "{\"status\":\"incomplete\",\"status\":\"completed\"}",
          "x".repeat(1_048_577)
        })
      assertThat(discover(client((b, k, t) -> new Response(200, body)), permit()).state())
          .isEqualTo(State.FAILED);
    assertThat(
            discover(client((b, k, t) -> new Response(429, completion().toString())), permit())
                .state())
        .isEqualTo(State.FAILED);
    var extra = completion();
    extra.withArray("output").addObject().put("type", "web_search_call").put("status", "completed");
    assertThat(discover(client((b, k, t) -> new Response(200, extra.toString())), permit()).state())
        .isEqualTo(State.FAILED);
  }

  @Test
  void late_or_cancelled_response_is_hidden() {
    var current = new AtomicBoolean(true);
    var client =
        client(
            (b, k, t) -> {
              current.set(false);
              return new Response(200, completion().toString());
            });
    var result = client.discover(ID, permit(), NOW.plusSeconds(60), current::get);
    assertThat(result.state()).isEqualTo(State.CANCELLED);
    assertThat(result.citations()).isEmpty();
    var late =
        client(
            (b, k, t) -> {
              clock.now = NOW.plusSeconds(20);
              return new Response(200, completion().toString());
            });
    assertThat(discover(late, permit()).state()).isEqualTo(State.EXPIRED);
  }

  @Test
  @Timeout(10)
  void concurrent_reuse_sends_at_most_one_request() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var client =
        client(
            (b, k, t) -> {
              entered.countDown();
              assertThat(release.await(3, TimeUnit.SECONDS)).isTrue();
              return new Response(200, completion().toString());
            });
    var permit = permit();
    try (var worker = Executors.newSingleThreadExecutor()) {
      var first = worker.submit(() -> discover(client, permit));
      try {
        assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(discover(client, permit).state()).isEqualTo(State.APPROVAL_REQUIRED);
      } finally {
        release.countDown();
      }
      assertThat(first.get(3, TimeUnit.SECONDS).state()).isEqualTo(State.SOURCES);
    }
    assertThat(calls).hasValue(1);
  }
}
