package org.cdpg.dx.common.util;

import java.time.ZonedDateTime;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class TimeUtils {
  private static final Logger LOGGER = LogManager.getLogger(TimeUtils.class);

  /**
   * Parses an ISO-8601 datetime string safely. Accepts forms like: - 2025-10-01T00:00:00Z -
   * 2025-10-01T00:00:00+05:30 - 2025-10-01T00:00:00 05:30 (auto-fixes to +05:30)
   *
   * <p>Does NOT normalize or convert zones — returns as given.
   */
  public static String parseIsoDateTime(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Datetime value cannot be null or blank");
    }

    String normalized = value.trim().replaceAll("\\s", "+");
    ZonedDateTime zdt;
    try {
      // Try ZonedDateTime first
      zdt = ZonedDateTime.parse(normalized);
      LOGGER.debug("Parsed time: " + zdt);
      return normalized;
    } catch (Exception e1) {
      LOGGER.error(e1.getMessage());
      throw new IllegalArgumentException("Invalid datetime format: {}" + value);
    }
  }
}
