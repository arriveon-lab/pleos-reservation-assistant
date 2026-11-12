package ai.pleos.assistant.app;

/** UI-only scenario. No server, reservation journal, phone number or SDK ETA is used. */
final class DemoFlowState {
  enum Stage {
    CONDITIONS,
    CANDIDATES,
    APPROVAL,
    CALLING,
    CONFIRMED,
    CHANGE_REQUEST,
    CHANGE_WAIT,
    CHANGED,
    DECLINED,
    UNKNOWN
  }

  Stage stage = Stage.CONDITIONS;
  int adults = 2;
  int children = 2;
  int bufferMinutes = 10;
  int candidate;
  boolean parking = true;
  boolean highChair;
  boolean pet;
  boolean stepFree;
  boolean changeAccepted;
  boolean changeRejected;
  String confirmedTime = "";

  String requestedTime() {
    return String.format(java.util.Locale.ROOT, "19:%02d", bufferMinutes);
  }

  void search() {
    if (stage == Stage.CONDITIONS) stage = Stage.CANDIDATES;
  }

  void select(int value) {
    if (stage != Stage.CANDIDATES || value < 0 || value > 2) return;
    candidate = value;
    stage = Stage.APPROVAL;
  }

  void approve() {
    if (stage == Stage.APPROVAL) stage = Stage.CALLING;
  }

  void accepted() {
    if (stage != Stage.CALLING) return;
    confirmedTime = requestedTime();
    stage = Stage.CONFIRMED;
  }

  void callback() {
    if (stage != Stage.CONFIRMED || confirmedTime.equals("19:30")) return;
    changeRejected = false;
    stage = Stage.CHANGE_REQUEST;
  }

  void decide(boolean accepted) {
    if (stage != Stage.CHANGE_REQUEST) return;
    changeAccepted = accepted;
    stage = Stage.CHANGE_WAIT;
  }

  void reconfirm() {
    if (stage != Stage.CHANGE_WAIT) return;
    if (changeAccepted) {
      confirmedTime = "19:30";
      stage = Stage.CHANGED;
    } else {
      changeRejected = true;
      stage = Stage.CONFIRMED;
    }
  }

  void unknown() {
    if (stage == Stage.CALLING || stage == Stage.CHANGE_WAIT) stage = Stage.UNKNOWN;
  }

  void back() {
    if (stage == Stage.CANDIDATES) stage = Stage.CONDITIONS;
    else if (stage == Stage.APPROVAL) stage = Stage.CANDIDATES;
  }
}
