package ai.pleos.assistant;

import static org.assertj.core.api.Assertions.*;

import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class OwnershipMigrationTest {
  @Test
  void oldDataIsPreservedButNeitherAssignedNorReplayed() throws Exception {
    String url = "jdbc:h2:mem:migration-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    Flyway.configure().dataSource(url, "sa", "").target("2").load().migrate();
    try (var connection = DriverManager.getConnection(url, "sa", "");
        var sql = connection.createStatement()) {
      sql.executeUpdate("INSERT INTO searches VALUES ('legacy-search','{}')");
      sql.executeUpdate(
          "INSERT INTO reservations VALUES ('legacy-reservation','legacy-search','{}','old-trip')");
      sql.executeUpdate("INSERT INTO calls VALUES ('legacy-call','legacy-search','{}')");
      sql.executeUpdate("INSERT INTO commands VALUES ('search','old-key','hash','{}')");
    }
    Flyway.configure().dataSource(url, "sa", "").load().migrate();
    try (var connection = DriverManager.getConnection(url, "sa", "");
        var sql = connection.createStatement()) {
      try (var rows =
          sql.executeQuery("SELECT journey_id,payload FROM searches WHERE id='legacy-search'")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getString(1)).isNull();
        assertThat(rows.getString(2)).isEqualTo("{}");
      }
      for (String table : new String[] {"app_users", "journeys", "commands"}) {
        try (var rows = sql.executeQuery("SELECT COUNT(*) FROM " + table)) {
          rows.next();
          assertThat(rows.getInt(1)).isZero();
        }
      }
      for (String table : new String[] {"reservations", "calls", "legacy_commands"}) {
        try (var rows = sql.executeQuery("SELECT COUNT(*) FROM " + table)) {
          rows.next();
          assertThat(rows.getInt(1)).isEqualTo(1);
        }
      }
    }
  }
}
