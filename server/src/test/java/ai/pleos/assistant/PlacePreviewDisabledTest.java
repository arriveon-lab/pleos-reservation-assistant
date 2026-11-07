package ai.pleos.assistant;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:preview-disabled;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class PlacePreviewDisabledTest {
  @Autowired ApplicationContext context;
  @Autowired MockMvc mvc;

  @Test
  void defaultApplicationDoesNotRegisterExperimentalPreviewOrItsRegistry() throws Exception {
    assertThat(context.getBeansOfType(PlacePreviewController.class)).isEmpty();
    assertThat(context.getBeansOfType(PlacePreviewRegistry.class)).isEmpty();
    mvc.perform(
            post("/api/experimental/place-previews").contentType("application/json").content("{}"))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.contractVersion").value("1.0.0"))
        .andExpect(jsonPath("$.externalCallsEnabled").value(false));
  }
}
