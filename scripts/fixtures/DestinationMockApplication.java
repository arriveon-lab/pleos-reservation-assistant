package ai.pleos.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;

/**
 * Local #53 acceptance only, compiled against its test classpath, never packaged in product JAR.
 */
public final class DestinationMockApplication {
  public static void main(String[] args) {
    if (args.length != 2) throw new IllegalArgumentException("fixture port");
    String fixture = args[0];
    if (!java.util.Set.of("THREE", "TWO", "EMPTY", "FAILED", "LATE", "EXPIRED", "NO_MARKER")
        .contains(fixture)) throw new IllegalArgumentException();
    int port = Integer.parseInt(args[1]);
    if (port < 1024 || port > 65535) throw new IllegalArgumentException();
    new SpringApplication(Application.class, Fixtures.class)
        .run(
            "--server.address=127.0.0.1",
            "--server.port=" + port,
            "--app.mode=MOCK",
            "--spring.main.banner-mode=off",
            "--logging.level.root=WARN",
            "--app.destination-recommendations.enabled=true",
            "--app.destination-recommendations.source=WEB_SEARCH",
            "--app.destination-recommendations.web.enabled=false",
            "--spring.datasource.url=jdbc:h2:file:"
                + Path.of("work/destination-auth").toAbsolutePath(),
            "--test.destination.fixture=" + fixture);
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Fixtures {
    @Bean
    FilterRegistrationBean<Filter> mockMarker(
        @org.springframework.beans.factory.annotation.Value("${test.destination.fixture}")
            String fixture) {
      FilterRegistrationBean<Filter> bean = new FilterRegistrationBean<>();
      bean.setFilter(
          (request, response, chain) -> {
            if (((HttpServletRequest) request).getRequestURI().equals("/mock-capabilities")) {
              if (fixture.equals("NO_MARKER")) {
                ((HttpServletResponse) response).setStatus(404);
                return;
              }
              response.setContentType("application/json");
              response
                  .getWriter()
                  .write(
                      "{\"destinationRecommendationsMode\":\"MOCK\",\"externalCallsEnabled\":false}");
            } else {
              chain.doFilter(request, response);
              var http = (HttpServletRequest) request;
              if (http.getRequestURI()
                  .startsWith("/api/experimental/destination-recommendations")) {
                String route =
                    http.getRequestURI().endsWith("/close")
                        ? "close"
                        : http.getRequestURI()
                                .equals("/api/experimental/destination-recommendations")
                            ? "start"
                            : "poll";
                System.out.println(
                    "MOCK_HTTP "
                        + http.getMethod()
                        + " "
                        + route
                        + " "
                        + ((HttpServletResponse) response).getStatus());
              }
            }
          });
      bean.setOrder(Integer.MIN_VALUE);
      return bean;
    }

    @Bean
    DestinationRecommendationService.WebGateway mockWeb(
        Clock clock,
        @org.springframework.beans.factory.annotation.Value("${test.destination.fixture}")
            String fixture) {
      return (request, deadline, current) -> {
        if (fixture.equals("LATE"))
          try {
            Thread.sleep(5000);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
        var now = clock.instant();
        if (fixture.equals("FAILED")) throw new IllegalStateException("MOCK_FAILURE");
        if (fixture.equals("EMPTY"))
          return new WebRecommendation.Result(
              WebRecommendation.Mode.WEB_SEARCH,
              WebRecommendation.State.EMPTY,
              now,
              now.plusSeconds(300),
              java.util.List.of(),
              0);
        String response = MockWebRecommendationGateway.fictionalResponse();
        if (fixture.equals("TWO"))
          try {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode root = (ObjectNode) mapper.readTree(response);
            ObjectNode body =
                (ObjectNode) mapper.readTree(root.at("/output/1/content/0/text").asText());
            ((ObjectNode) body.at("/candidates/0")).put("address", "UNKNOWN");
            ((ObjectNode) root.at("/output/1/content/0")).put("text", body.toString());
            response = root.toString();
          } catch (Exception invalid) {
            throw new IllegalStateException(invalid);
          }
        return new WebRecommendationParser()
            .parse(
                response,
                request,
                WebRecommendation.Mode.WEB_SEARCH,
                now,
                now.plusSeconds(fixture.equals("EXPIRED") ? 3 : 300));
      };
    }
  }
}
