package ai.pleos.assistant;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Explicit local fixture runner. No synthetic Principal or operator role; excluded from bootJar.
 */
public final class PreviewAcceptanceApplication {
  public enum Fixture {
    RESULTS,
    EMPTY,
    FILTERED,
    FAILED
  }

  public static void main(String[] args) {
    // Compose exposes only host loopback; this flag opens the container's own interface.
    boolean container = args.length == 4 && "--container".equals(args[3]);
    if (args.length != 3 && !container)
      throw new IllegalArgumentException(
          "Expected fixture, port, isolated DB path, optional container flag");
    var fixture = Fixture.valueOf(args[0].toUpperCase(Locale.ROOT));
    int port = Integer.parseInt(args[1]);
    if (port < 1024 || port > 65535) throw new IllegalArgumentException("Invalid local port");
    Path database = Path.of(args[2]).toAbsolutePath().normalize();
    if (!database.startsWith(Path.of("work").toAbsolutePath().normalize())
        || !database.toString().matches("[\\p{L}\\p{N}/_. -]+"))
      throw new IllegalArgumentException("Use a separate DB path under work without URL options");
    new SpringApplication(Application.class, Fixtures.class)
        .run(
            "--server.address=" + (container ? "0.0.0.0" : "127.0.0.1"),
            "--server.port=" + port,
            "--app.mode=MOCK",
            "--logging.level.root=WARN",
            "--spring.main.banner-mode=off",
            "--app.place-preview.enabled=true",
            "--spring.datasource.url=jdbc:h2:file:" + database,
            "--test.preview.fixture=" + fixture.name());
  }

  static final class FixtureAdapter implements PlaceSearchAdapter {
    private final Clock clock;
    private final Fixture fixture;

    FixtureAdapter(Clock clock, Fixture fixture) {
      this.clock = clock;
      this.fixture = fixture;
    }

    @Override
    public PlaceSearchResult search(PlaceQuery query) {
      return switch (fixture) {
        case RESULTS -> new MockPlaceSearchAdapter(clock).search(query);
        case EMPTY -> new PlaceSearchResult.Success(List.of(), clock.instant(), 0);
        case FAILED -> new PlaceSearchResult.Failure(PlaceSearchFailure.UPSTREAM_UNAVAILABLE);
        case FILTERED ->
            new PlaceSearchResult.Success(
                List.of(
                    new PlaceSummary(
                        PlaceSummary.Provider.MOCK,
                        "acceptance-outside",
                        "가상 범위 밖 식당",
                        "한식",
                        query.latitude() > 0 ? query.latitude() - 1 : query.latitude() + 1,
                        query.longitude(),
                        null,
                        null,
                        null,
                        null)),
                clock.instant(),
                0);
      };
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Fixtures {
    @Bean
    FixtureAdapter acceptanceAdapter(
        Clock clock, @Value("${test.preview.fixture}") Fixture fixture) {
      return new FixtureAdapter(clock, fixture);
    }

    @Bean(destroyMethod = "close")
    @Primary
    PlacePreviewRegistry acceptanceRegistry(FixtureAdapter adapter) {
      return new PlacePreviewRegistry(
          adapter,
          new PlacePreviewRegistry.Limits(
              8, 2, 2, 32, Duration.ofMinutes(5), Duration.ofSeconds(3)));
    }
  }
}
