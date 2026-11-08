package ai.pleos.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration(proxyBeanMethods = false)
class ApiAccessConfiguration {
  @Bean
  FilterRegistrationBean<OncePerRequestFilter> apiAccess(ObjectMapper mapper) {
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
                String path = request.getRequestURI().substring(request.getContextPath().length());
                boolean publicStatus =
                    request.getMethod().equals("GET") && path.equals("/api/v1/status");
                boolean publicAuth =
                    request.getMethod().equals("POST")
                        && (path.equals("/api/v1/anonymous-sessions")
                            || path.equals("/api/v1/anonymous-sessions/refresh"));
                if (!publicStatus && !publicAuth) {
                  try {
                    RequestActor actor = RequestActor.from(request);
                    if (path.startsWith("/api/v1/mock/")) actor.requireMockOperator();
                  } catch (ApiError error) {
                    response.setStatus(error.status.value());
                    response.setContentType("application/json");
                    response.setCharacterEncoding("UTF-8");
                    mapper.writeValue(
                        response.getWriter(),
                        Map.of("code", error.code, "message", error.getMessage(), "mode", "MOCK"));
                    return;
                  }
                }
                chain.doFilter(request, response);
              }
            });
    registration.addUrlPatterns("/api/v1/*");
    // Same boundary as #16: the authentication adapter must establish Principal before this guard.
    registration.setOrder(Ordered.LOWEST_PRECEDENCE - 10);
    registration.setAsyncSupported(true);
    return registration;
  }
}
