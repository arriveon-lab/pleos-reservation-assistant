package ai.pleos.assistant;

import java.io.IOException;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.jsoup.Jsoup;

/** Internal operator-approved URL fetcher, never registered as a user-supplied-URL endpoint. */
public final class ApprovedPlacePageAdapter implements PlaceDetailEnricher.PageAdapter {
  private final Clock clock;

  public ApprovedPlacePageAdapter(Clock clock) {
    this.clock = java.util.Objects.requireNonNull(clock);
  }

  @Override
  public PlaceDetailEnricher.Page fetch(PlaceDetailEnricher.Source source, Instant deadline)
      throws Exception {
    long remaining = Duration.between(clock.instant(), deadline).toMillis();
    if (remaining < 1) throw new IOException("Detail deadline elapsed");
    for (InetAddress address : InetAddress.getAllByName(source.approvedHost())) {
      byte[] bytes = address.getAddress();
      if (address.isAnyLocalAddress()
          || address.isLoopbackAddress()
          || address.isLinkLocalAddress()
          || address.isSiteLocalAddress()
          || address.isMulticastAddress()
          || (bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc)
          || (bytes.length == 4
              && ((bytes[0] & 255) == 0
                  || (bytes[0] & 255) >= 224
                  || ((bytes[0] & 255) == 100
                      && (bytes[1] & 255) >= 64
                      && (bytes[1] & 255) <= 127))))
        throw new IOException("Non-public approved source address");
    }
    remaining = Duration.between(clock.instant(), deadline).toMillis();
    if (remaining < 1) throw new IOException("Detail deadline elapsed");
    var response =
        Jsoup.connect(source.url().toString())
            .followRedirects(false)
            .timeout((int) Math.min(5000, remaining))
            .maxBodySize(524289)
            .ignoreHttpErrors(true)
            .ignoreContentType(true)
            .userAgent("ArriveOn-ReadOnly-Validation/0.1")
            .execute();
    String type = response.contentType();
    String mediaType =
        type == null ? "" : type.toLowerCase(java.util.Locale.ROOT).split(";", 2)[0].strip();
    if (response.statusCode() != 200
        || response.bodyAsBytes().length > 524288
        || !(mediaType.equals("text/html") || mediaType.equals("application/xhtml+xml")))
      throw new IOException("Invalid bounded detail page response");
    return new PlaceDetailEnricher.Page(
        source.url(), response.body(), clock.instant(), PlaceDetailEnricher.Mode.READ_ONLY_WEB);
  }
}
