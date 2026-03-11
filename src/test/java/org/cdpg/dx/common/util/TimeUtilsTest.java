package org.cdpg.dx.common.util;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("TimeUtils Tests")
class TimeUtilsTest {

  @Nested
  @DisplayName("parseIsoDateTime Tests")
  class ParseIsoDateTimeTests {

    @Test
    @DisplayName("should parse ISO-8601 datetime with Z suffix")
    void shouldParseIsoDateTimeWithZ() {
      String result = TimeUtils.parseIsoDateTime("2025-10-01T00:00:00Z");
      assertEquals("2025-10-01T00:00:00Z", result);
    }

    @Test
    @DisplayName("should parse ISO-8601 datetime with timezone offset")
    void shouldParseIsoDateTimeWithOffset() {
      String result = TimeUtils.parseIsoDateTime("2025-10-01T00:00:00+05:30");
      assertEquals("2025-10-01T00:00:00+05:30", result);
    }

    @Test
    @DisplayName("should normalize space to plus in timezone offset")
    void shouldNormalizeSpaceToPlusInOffset() {
      String result = TimeUtils.parseIsoDateTime("2025-10-01T00:00:00 05:30");
      assertEquals("2025-10-01T00:00:00+05:30", result);
    }

    @Test
    @DisplayName("should throw IllegalArgumentException for null value")
    void shouldThrowForNullValue() {
      IllegalArgumentException ex =
          assertThrows(IllegalArgumentException.class, () -> TimeUtils.parseIsoDateTime(null));
      assertTrue(ex.getMessage().contains("null or blank"));
    }

    @Test
    @DisplayName("should throw IllegalArgumentException for blank value")
    void shouldThrowForBlankValue() {
      IllegalArgumentException ex =
          assertThrows(IllegalArgumentException.class, () -> TimeUtils.parseIsoDateTime("   "));
      assertTrue(ex.getMessage().contains("null or blank"));
    }

    @Test
    @DisplayName("should throw IllegalArgumentException for invalid datetime format")
    void shouldThrowForInvalidFormat() {
      IllegalArgumentException ex =
          assertThrows(
              IllegalArgumentException.class,
              () -> TimeUtils.parseIsoDateTime("not-a-date"));
      assertTrue(ex.getMessage().contains("Invalid datetime format"));
    }

    @Test
    @DisplayName("should throw for date-only input without time component")
    void shouldThrowForDateOnlyInput() {
      assertThrows(
          IllegalArgumentException.class, () -> TimeUtils.parseIsoDateTime("2025-10-01"));
    }

    @Test
    @DisplayName("should handle trimming of whitespace around valid datetime")
    void shouldHandleTrimmingOfWhitespace() {
      String result = TimeUtils.parseIsoDateTime("  2025-10-01T00:00:00Z  ");
      assertEquals("2025-10-01T00:00:00Z", result);
    }
  }
}
