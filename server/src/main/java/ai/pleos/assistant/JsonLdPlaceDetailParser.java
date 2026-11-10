package ai.pleos.assistant;

import static ai.pleos.assistant.PlaceDetailEnricher.*;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import org.jsoup.Jsoup;

/** Reads local JSON-LD only. Does not execute scripts or fetch contexts, links or images. */
final class JsonLdPlaceDetailParser {
  private final ObjectMapper json =
      new ObjectMapper(
          JsonFactory.builder()
              .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
              .streamReadConstraints(
                  StreamReadConstraints.builder()
                      .maxNestingDepth(32)
                      .maxStringLength(32768)
                      .maxNumberLength(64)
                      .build())
              .build());

  Parsed parse(Identity expected, Page page) {
    var claims = new EnumMap<Field, List<String>>(Field.class);
    boolean matched = false;
    Instant updated = null;
    try {
      var scripts = Jsoup.parse(page.html()).select("script[type=application/ld+json]");
      if (scripts.size() > 20) return new Parsed(false, true, null, claims);
      for (var script : scripts) {
        JsonNode root = json.readTree(script.data());
        var nodes = new ArrayList<JsonNode>();
        collect(root, false, nodes);
        if (nodes.size() > 100) return new Parsed(false, true, null, claims);
        for (JsonNode node : nodes) {
          if (!sameIdentity(expected, node)) continue;
          matched = true;
          if (node.path("dateModified").isTextual()) {
            try {
              Instant value = Instant.parse(node.path("dateModified").asText());
              if (updated == null || value.isAfter(updated)) updated = value;
            } catch (java.time.format.DateTimeParseException unknownTimezone) {
              /* remains unknown */
            }
          }
          text(claims, Field.CUISINE, node.get("servesCuisine"));
          text(claims, Field.PRICE_RANGE, node.get("priceRange"));
          text(claims, Field.OPENING_HOURS, node.get("openingHours"));
          if (node.has("openingHoursSpecification")) {
            String hours = hours(node.get("openingHoursSpecification"));
            if (hours != null) add(claims, Field.OPENING_HOURS, hours);
          }
          JsonNode menu = node.has("hasMenu") ? node.get("hasMenu") : node.get("menu");
          if (menu != null && menu.isTextual()) {
            URI value = optionalUrl(page.url(), menu.asText());
            if (value != null) add(claims, Field.MENU_URL, value.toString());
          }
          JsonNode reservation = node.get("acceptsReservations");
          if (reservation != null && reservation.isBoolean())
            add(claims, Field.RESERVATION_POLICY, reservation.asText());
          else if (reservation != null && reservation.isTextual()) {
            String value = reservation.asText();
            if (value.equalsIgnoreCase("Yes") || value.equalsIgnoreCase("No"))
              add(
                  claims,
                  Field.RESERVATION_POLICY,
                  Boolean.toString(value.equalsIgnoreCase("Yes")));
            else if (optionalUrl(null, value) != null) add(claims, Field.RESERVATION_POLICY, value);
          }
          JsonNode features = node.path("amenityFeature");
          for (JsonNode feature : features.isArray() ? features : List.of(features)) {
            if (!feature.isObject() || !feature.path("value").isBoolean()) continue;
            String name = canonical(feature.path("name").asText());
            if (List.of("parking", "주차").contains(name))
              add(claims, Field.PARKING, feature.path("value").asText());
            if (List.of("childfriendly", "유아동반").contains(name))
              add(claims, Field.CHILD_FRIENDLY, feature.path("value").asText());
          }
        }
      }
      return new Parsed(matched, false, updated, claims);
    } catch (Exception malformed) {
      return new Parsed(false, true, null, new EnumMap<>(Field.class));
    }
  }

  private void collect(JsonNode node, boolean inheritedContext, List<JsonNode> nodes) {
    if (node == null) return;
    if (node.isArray()) {
      for (JsonNode child : node) collect(child, inheritedContext, nodes);
      return;
    }
    if (!node.isObject()) return;
    boolean context = inheritedContext;
    if (node.has("@context")) {
      JsonNode value = node.get("@context");
      context =
          value.isTextual()
              && List.of(
                      "https://schema.org",
                      "https://schema.org/",
                      "http://schema.org",
                      "http://schema.org/")
                  .contains(value.asText());
    }
    if (context && restaurant(node.path("@type"))) nodes.add(node);
    if (nodes.size() > 100) return;
    if (node.has("@graph")) collect(node.get("@graph"), context, nodes);
  }

  private boolean restaurant(JsonNode type) {
    if (type.isArray()) {
      for (JsonNode t : type) if (restaurant(t)) return true;
      return false;
    }
    return type.isTextual()
        && List.of(
                "Restaurant",
                "FoodEstablishment",
                "CafeOrCoffeeShop",
                "https://schema.org/Restaurant",
                "https://schema.org/FoodEstablishment",
                "https://schema.org/CafeOrCoffeeShop")
            .contains(type.asText());
  }

  private boolean sameIdentity(Identity expected, JsonNode node) {
    if (!node.path("name").isTextual()
        || !canonical(expected.name()).equals(canonical(node.path("name").asText()))) return false;
    JsonNode address = node.get("address");
    if (address == null || expected.address() == null) return false;
    if (address.isTextual())
      return canonical(expected.address()).equals(canonical(address.asText()));
    if (!address.isObject() || !address.path("streetAddress").isTextual()) return false;
    String street = address.path("streetAddress").asText();
    String region = address.path("addressRegion").asText("");
    String locality = address.path("addressLocality").asText("");
    String target = canonical(expected.address());
    if (target.equals(canonical(region + locality + street))) return true;
    return target.equals(canonical(street))
        && (region.isBlank() || target.contains(canonical(region)))
        && (locality.isBlank() || target.contains(canonical(locality)));
  }

  private void text(EnumMap<Field, List<String>> claims, Field field, JsonNode value) {
    if (value == null) return;
    if (value.isTextual()) add(claims, field, value.asText());
    else if (value.isArray() && value.size() <= 14) {
      var texts = new ArrayList<String>();
      for (JsonNode part : value) {
        if (!part.isTextual()) return;
        texts.add(part.asText());
      }
      add(claims, field, String.join("; ", texts));
    }
  }

  private void add(EnumMap<Field, List<String>> claims, Field field, String value) {
    if (value == null
        || value.isBlank()
        || value.length() > (field == Field.OPENING_HOURS ? 2048 : 512)) return;
    claims.computeIfAbsent(field, k -> new ArrayList<>()).add(value.strip());
  }

  private URI optionalUrl(URI base, String value) {
    try {
      URI url = base == null ? URI.create(value) : base.resolve(value);
      return safeUrl(url) ? url : null;
    } catch (IllegalArgumentException invalid) {
      return null;
    }
  }

  private String hours(JsonNode value) {
    if (!value.isObject() && !value.isArray()) return null;
    List<JsonNode> specs = new ArrayList<>();
    if (value.isArray()) value.forEach(specs::add);
    else specs.add(value);
    if (specs.isEmpty() || specs.size() > 14) return null;
    var result = json.createArrayNode();
    try {
      for (JsonNode spec : specs) {
        if (!spec.isObject() || !spec.path("opens").isTextual() || !spec.path("closes").isTextual())
          return null;
        String opens = java.time.LocalTime.parse(spec.path("opens").asText()).toString();
        String closes = java.time.LocalTime.parse(spec.path("closes").asText()).toString();
        var days = json.createArrayNode();
        JsonNode input = spec.path("dayOfWeek");
        List<JsonNode> names = new ArrayList<>();
        if (input.isArray()) input.forEach(names::add);
        else names.add(input);
        if (names.isEmpty() || names.size() > 7) return null;
        for (JsonNode name : names) {
          if (!name.isTextual()) return null;
          String day =
              name.asText().replace("https://schema.org/", "").replace("http://schema.org/", "");
          if (!List.of("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
              .contains(day)) return null;
          days.add(day);
        }
        var item = result.addObject();
        item.set("days", days);
        item.put("opens", opens);
        item.put("closes", closes);
        for (String date : List.of("validFrom", "validThrough")) {
          if (spec.has(date))
            item.put(date, java.time.LocalDate.parse(spec.path(date).asText()).toString());
        }
      }
      return json.writeValueAsString(result);
    } catch (Exception invalid) {
      return null;
    }
  }
}
