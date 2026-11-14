package ai.pleos.assistant.ux;

import static org.junit.jupiter.api.Assertions.*;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class GleoSearchCommandTest {
  @Test
  void acceptsWholeCountsAndBoundaries() throws Exception {
    for (int adults : new int[] {1, 2, 20})
      for (int children : new int[] {0, 2, 10}) {
        var command =
            GleoSearchCommand.parse(
                new JSONObject().put("adults", adults).put("children", children));
        assertEquals(adults, command.adults());
        assertEquals(children, command.children());
      }
  }

  @Test
  void rejectsMissingCoercedFractionalAndOutOfRangeInputs() throws Exception {
    for (Object value : new Object[] {"2", true, JSONObject.NULL, -1, 0, 21, 2.5}) {
      var input = new JSONObject().put("adults", value).put("children", 2);
      assertThrows(Exception.class, () -> GleoSearchCommand.parse(input));
    }
    for (Object value : new Object[] {"2", true, JSONObject.NULL, -1, 11, 2.5}) {
      var input = new JSONObject().put("adults", 2).put("children", value);
      assertThrows(Exception.class, () -> GleoSearchCommand.parse(input));
    }
    assertThrows(Exception.class, () -> GleoSearchCommand.parse(new JSONObject().put("adults", 2)));
    assertThrows(
        Exception.class,
        () ->
            GleoSearchCommand.parse(
                new JSONObject().put("adults", 2).put("children", 2).put("book", true)));
  }
}
