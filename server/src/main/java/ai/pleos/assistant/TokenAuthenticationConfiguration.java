package ai.pleos.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.Principal;
import java.util.Collections;
import java.util.Map;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration(proxyBeanMethods = false)
class TokenAuthenticationConfiguration {
  @Bean
  FilterRegistrationBean<OncePerRequestFilter> tokenAuthentication(
      AuthService auth, ObjectMapper mapper) {
    var registration =
        new FilterRegistrationBean<OncePerRequestFilter>(
            new OncePerRequestFilter() {
              @Override
              protected void doFilterInternal(
                  HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                  throws ServletException, IOException {
                var values = Collections.list(request.getHeaders("Authorization"));
                if (values.isEmpty()) {
                  chain.doFilter(request, response);
                  return;
                }
                AuthStore.Token token;
                try {
                  if (values.size() != 1
                      || !values.getFirst().regionMatches(true, 0, "Bearer ", 0, 7))
                    throw AuthService.unauthorized();
                  token = auth.authenticate(values.getFirst().substring(7));
                } catch (ApiError error) {
                  response.setStatus(401);
                  response.setContentType("application/json");
                  response.setCharacterEncoding("UTF-8");
                  response.setHeader("Cache-Control", "no-store");
                  response.setHeader("Pragma", "no-cache");
                  response.setHeader("Referrer-Policy", "no-referrer");
                  response.setHeader("X-Content-Type-Options", "nosniff");
                  response.setHeader("WWW-Authenticate", "Bearer");
                  String path =
                      request.getRequestURI().substring(request.getContextPath().length());
                  String code =
                      path.startsWith("/api/experimental/place-previews")
                          ? "PREVIEW_AUTHENTICATION_REQUIRED"
                          : "AUTHENTICATION_REQUIRED";
                  mapper.writeValue(
                      response.getWriter(),
                      Map.of("code", code, "message", "유효한 인증 정보가 필요합니다.", "mode", "MOCK"));
                  return;
                }
                chain.doFilter(
                    new HttpServletRequestWrapper(request) {
                      @Override
                      public Principal getUserPrincipal() {
                        return token::subject;
                      }

                      @Override
                      public String getRemoteUser() {
                        return token.subject();
                      }

                      @Override
                      public boolean isUserInRole(String role) {
                        return false;
                      }
                    },
                    response);
              }
            });
    registration.addUrlPatterns(
        "/api/v1/*",
        "/api/experimental/place-previews/*",
        "/api/experimental/web-recommendations/*");
    registration.setOrder(Ordered.LOWEST_PRECEDENCE - 20);
    registration.setAsyncSupported(true);
    return registration;
  }
}
