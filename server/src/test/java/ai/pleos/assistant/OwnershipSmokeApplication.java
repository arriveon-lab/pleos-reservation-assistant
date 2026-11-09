package ai.pleos.assistant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.Principal;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/** Synthetic HTTP harness only; src/test is excluded from the distributed server JAR. */
public class OwnershipSmokeApplication {
  public static void main(String[] args) {
    SpringApplication.run(new Class<?>[] {Application.class, Identity.class}, args);
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Identity {
    @Bean
    FilterRegistrationBean<OncePerRequestFilter> syntheticIdentity() {
      var registration =
          new FilterRegistrationBean<OncePerRequestFilter>(
              new OncePerRequestFilter() {
                @Override
                protected void doFilterInternal(
                    HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                  chain.doFilter(
                      new HttpServletRequestWrapper(request) {
                        @Override
                        public Principal getUserPrincipal() {
                          return () -> "smoke-owner";
                        }

                        @Override
                        public boolean isUserInRole(String role) {
                          return role.equals("MOCK_OPERATOR");
                        }
                      },
                      response);
                }
              });
      registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
      return registration;
    }
  }
}
