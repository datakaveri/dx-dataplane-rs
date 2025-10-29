package org.cdpg.dx.common.util;

import java.time.OffsetDateTime;
import java.time.ZonedDateTime;

public class TimeUtils {

  /**
   * Parses an ISO-8601 datetime string safely. Accepts forms like: - 2025-10-01T00:00:00Z -
   * 2025-10-01T00:00:00+05:30 - 2025-10-01T00:00:00 05:30 (auto-fixes to +05:30)
   *
   * <p>Does NOT normalize or convert zones — returns as given.
   */
  public static ZonedDateTime parseIsoDateTime(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Datetime value cannot be null or blank");
    }

    String normalized = value.trim().replace(" ", "+").replaceAll("t", "T");

    try {
      // Try ZonedDateTime first
      return ZonedDateTime.parse(normalized);
    } catch (Exception e1) {
      try {
        // Fallback to OffsetDateTime (ZonedDateTime.parse can fail if zone missing)
        return OffsetDateTime.parse(normalized).toZonedDateTime();
      } catch (Exception e2) {
        throw new IllegalArgumentException("Invalid datetime format: " + value);
      }
    }
  }
}
