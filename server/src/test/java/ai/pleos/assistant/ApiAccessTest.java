package ai.pleos.assistant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:access;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class ApiAccessTest {
  @Autowired MockMvc http;

  @Test
  void onlyStatusIsPublicAndIdentityHeadersAreNotAuthentication() throws Exception {
    http.perform(get("/api/v1/status")).andExpect(status().isOk());
    for (String path :
        List.of(
            "/searches/missing",
            "/searches/missing/calls",
            "/reservations/missing",
            "/calls/missing")) {
      http.perform(
              get("/api/v1" + path)
                  .header("Authorization", "Bearer forged")
                  .header("X-User-Id", "victim")
                  .header("X-Owner", "victim"))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }
    for (String path :
        List.of(
            "/searches",
            "/searches/missing/inquiries",
            "/searches/missing/reservations",
            "/reservations/missing/changes",
            "/mock/calls/missing/outcomes")) {
      http.perform(
              post("/api/v1" + path)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"userId\":\"victim\"}"))
          .andExpect(status().isUnauthorized())
          .andExpect(header().string("Cache-Control", "no-store"));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "bad name", "bad/user", "한글"})
  void invalidPrincipalIsRejected(String name) throws Exception {
    http.perform(get("/api/v1/searches/missing").principal(() -> name))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void nullPrincipalNameIsRejected() throws Exception {
    http.perform(get("/api/v1/searches/missing").principal(() -> null))
        .andExpect(status().isUnauthorized());
  }
}
