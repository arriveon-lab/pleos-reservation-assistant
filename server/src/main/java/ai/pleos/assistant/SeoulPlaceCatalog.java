package ai.pleos.assistant;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** Internal local snapshot, not a Spring bean, public DTO, booking authority or network client. */
public record SeoulPlaceCatalog(
    String schemaVersion,
    String sourceKind,
    String datasetId,
    String catalogUrl,
    String licenseUrl,
    String attribution,
    String sourceCrs,
    String targetCrs,
    Instant acquiredAt,
    Instant sourceSnapshotAt,
    int inputRows,
    int rejectedRows,
    List<Entry> entries) {
  public static final String CATALOG_URL =
      "https://data.seoul.go.kr/dataList/OA-18655/A/1/datasetView.do";
  public static final String LICENSE_URL = "https://www.kogl.or.kr/info/licenseType1.do";
  public static final String ATTRIBUTION = "서울특별시 성동구 · 서울 열린데이터광장 OA-18655 · 공공누리 제1유형";
  private static final int MAX_BYTES = 2 * 1024 * 1024;

  public record Entry(
      String catalogPlaceId,
      String sourceRecordId,
      String name,
      String address,
      String phone,
      double latitude,
      double longitude,
      String rowModifiedRaw,
      String rowUpdatedRaw,
      String rowTimeZone,
      String administrativeStatus,
      String contactStatus) {
    public Entry {
      if (!text(sourceRecordId)
          || !text(name)
          || !text(address)
          || !stableId(sourceRecordId).equals(catalogPlaceId)
          || phone == null
          || !phone.matches("(?:02[0-9]{7,8}|0[3-6][0-9]{8,9}|070[0-9]{8})")
          || phone.matches(".*0{7}$")
          || phone.matches("(?:02|0[0-9]{2})0{3,4}.*")
          || !Double.isFinite(latitude)
          || !Double.isFinite(longitude)
          || !"UNKNOWN".equals(rowTimeZone)
          || !"NORMAL_REPORTED".equals(administrativeStatus)
          || !"UNVERIFIED".equals(contactStatus)
          || rowModifiedRaw == null
          || rowUpdatedRaw == null
          || rowModifiedRaw.length() > 4096
          || rowUpdatedRaw.length() > 4096
          || PlaceRecommendationPlanner.distance(
                  new PlaceQuery(37.544, 127.055, 1000, 3),
                  listing(catalogPlaceId, name, address, phone, latitude, longitude))
              > 1000) {
        throw new IllegalArgumentException("Invalid public catalog entry");
      }
    }

    PlaceSummary listing() {
      return listing(catalogPlaceId, name, address, phone, latitude, longitude);
    }

    private static PlaceSummary listing(
        String id, String name, String address, String phone, double latitude, double longitude) {
      return new PlaceSummary(
          PlaceSummary.Provider.SEOUL_OPEN_DATA,
          id,
          name,
          null,
          latitude,
          longitude,
          address,
          phone,
          null,
          null);
    }

    @Override
    public String toString() {
      return "SeoulCatalogEntry[redacted, contact=UNVERIFIED]";
    }
  }

  public SeoulPlaceCatalog {
    if (!"0.1.0-draft".equals(schemaVersion)
        || !"SEOUL_OPEN_DATA".equals(sourceKind)
        || !"OA-18655".equals(datasetId)
        || !CATALOG_URL.equals(catalogUrl)
        || !LICENSE_URL.equals(licenseUrl)
        || !ATTRIBUTION.equals(attribution)
        || !"EPSG:5174".equals(sourceCrs)
        || !"EPSG:4326".equals(targetCrs)
        || acquiredAt == null
        || (sourceSnapshotAt != null && sourceSnapshotAt.isAfter(acquiredAt))
        || entries == null
        || inputRows < 0
        || inputRows > 1000
        || rejectedRows < 0
        || inputRows != entries.size() + rejectedRows) {
      throw new IllegalArgumentException("Invalid public catalog provenance");
    }
    entries = List.copyOf(entries);
    var ids = new HashSet<String>();
    if (entries.stream().anyMatch(entry -> !ids.add(entry.catalogPlaceId())))
      throw new IllegalArgumentException("Duplicate public catalog identity");
  }

  public static SeoulPlaceCatalog load(Path path, ObjectMapper mapper) throws IOException {
    byte[] bytes;
    try (var stream = Files.newInputStream(path)) {
      bytes = stream.readNBytes(MAX_BYTES + 1);
    } catch (IOException exception) {
      throw new IOException("Public catalog unavailable");
    }
    if (bytes.length > MAX_BYTES) throw new IOException("Public catalog size limit");
    try {
      var strict = mapper.copy();
      strict.setConfig(
          strict.getDeserializationConfig().without(MapperFeature.ALLOW_COERCION_OF_SCALARS));
      strict
          .coercionConfigFor(LogicalType.Textual)
          .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
          .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
          .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
      return strict
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
          .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .readValue(bytes, SeoulPlaceCatalog.class);
    } catch (IOException | IllegalArgumentException exception) {
      // Parser errors can contain contact values and local paths; expose a safe code only.
      throw new IOException("Invalid public catalog");
    }
  }

  public static String stableId(String sourceRecordId) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest((CATALOG_URL + "#" + sourceRecordId).getBytes(StandardCharsets.UTF_8));
      return "sdo-" + HexFormat.of().formatHex(digest).substring(0, 32);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("Required digest unavailable");
    }
  }

  public Set<String> dispatchBlockers(String catalogPlaceId) {
    boolean exists = entries.stream().anyMatch(e -> e.catalogPlaceId().equals(catalogPlaceId));
    return exists
        ? Set.of(
            "MERCHANT_PHONE_UNVERIFIED", "PROCESSOR_REVIEW_REQUIRED", "EXECUTION_APPROVAL_REQUIRED")
        : Set.of("CATALOG_PLACE_NOT_FOUND");
  }

  private static boolean text(String value) {
    return value != null
        && !value.isBlank()
        && value.equals(value.strip())
        && value.length() <= 512
        && value.codePoints().noneMatch(Character::isISOControl);
  }

  @Override
  public String toString() {
    return "SeoulPlaceCatalog[entries=" + entries.size() + ", contacts=redacted]";
  }
}
