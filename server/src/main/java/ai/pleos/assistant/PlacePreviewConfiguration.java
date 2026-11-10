package ai.pleos.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.Principal;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/** Opt-in MOCK contract sandbox. Does not authenticate users or enable a live adapter. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.place-preview.enabled", havingValue = "true")
class PlacePreviewConfiguration {
  @Bean("placePreviewAdapter")
  PlaceSearchAdapter adapter(Clock clock) {
    // Deliberately independent of any future production PlaceSearchAdapter bean or API key.
    return new MockPlaceSearchAdapter(clock);
  }

  @Bean(destroyMethod = "close")
  PlacePreviewRegistry placePreviewRegistry(
      @Qualifier("placePreviewAdapter") PlaceSearchAdapter adapter) {
    return new PlacePreviewRegistry(
        adapter,
        new PlacePreviewRegistry.Limits(8, 2, 2, 32, Duration.ofMinutes(5), Duration.ofSeconds(3)));
  }

  @Bean
  FilterRegistrationBean<OncePerRequestFilter> placePreviewAccess(ObjectMapper mapper) {
    var registration =
        new FilterRegistrationBean<OncePerRequestFilter>(
            new OncePerRequestFilter() {
              @Override
              protected void doFilterInternal(
                  HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                  throws ServletException, IOException {
                response.setHeader("Cache-Control", "no-store");
                response.setHeader("Pragma", "no-cache");
                response.setHeader("Referrer-Policy", "no-referrer");
                response.setHeader("X-Content-Type-Options", "nosniff");
                if (!hasOwner(request.getUserPrincipal())) {
                  response.setStatus(401);
                  response.setContentType("application/json");
                  response.setCharacterEncoding("UTF-8");
                  mapper.writeValue(
                      response.getWriter(),
                      Map.of(
                          "code", "PREVIEW_AUTHENTICATION_REQUIRED",
                          "message", "검증된 사용자 인증이 필요합니다.",
                          "mode", "MOCK"));
                  return;
                }
                chain.doFilter(request, response);
              }
            });
    registration.addUrlPatterns("/api/experimental/place-previews/*");
    // A future authentication filter must run before this guard. No header/body auth fallback.
    registration.setOrder(Ordered.LOWEST_PRECEDENCE - 10);
    registration.setAsyncSupported(true);
    return registration;
  }

  private static boolean hasOwner(Principal principal) {
    if (principal == null) return false;
    String name = principal.getName();
    return name != null && name.matches("[A-Za-z0-9._:-]{1,100}");
  }
}
