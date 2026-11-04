package ai.pleos.assistant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** No network client, provider credentials or telephone numbers are accepted here. */
@Component
public class MockCallAdapter {
  public MockCallAdapter(@Value("${app.mode:MOCK}") String mode) {
    if (!"MOCK".equals(mode)) {
      throw new IllegalStateException(
          "LIVE_DISABLED: only MOCK is implemented; external calls require approval and a reviewed adapter.");
    }
  }

  public void prepare(Model.CallTask task) {
    if (task.mode != Model.Mode.MOCK) throw new IllegalStateException("LIVE_DISABLED");
    // The task is persisted by WorkflowService. Outcomes are supplied through the mock API.
  }
}
