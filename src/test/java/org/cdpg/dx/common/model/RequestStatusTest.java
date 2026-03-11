package org.cdpg.dx.common.model;

import static org.junit.jupiter.api.Assertions.*;

import org.cdpg.dx.common.exception.BaseDxException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("RequestStatus Tests")
class RequestStatusTest {

  @Nested
  @DisplayName("Enum Values Tests")
  class EnumValuesTests {

    @Test
    @DisplayName("should have four status values")
    void shouldHaveFourStatusValues() {
      RequestStatus[] values = RequestStatus.values();
      assertEquals(4, values.length);
    }

    @Test
    @DisplayName("each status should return correct string representation")
    void eachStatusShouldReturnCorrectString() {
      assertEquals("rejected", RequestStatus.REJECTED.getRequestStatus());
      assertEquals("pending", RequestStatus.PENDING.getRequestStatus());
      assertEquals("granted", RequestStatus.GRANTED.getRequestStatus());
      assertEquals("withdrawn", RequestStatus.WITHDRAWN.getRequestStatus());
    }
  }

  @Nested
  @DisplayName("fromString Tests")
  class FromStringTests {

    @Test
    @DisplayName("should parse valid status string case-insensitively")
    void shouldParseValidStatusCaseInsensitively() {
      assertEquals(RequestStatus.PENDING, RequestStatus.fromString("pending"));
      assertEquals(RequestStatus.PENDING, RequestStatus.fromString("PENDING"));
      assertEquals(RequestStatus.PENDING, RequestStatus.fromString("Pending"));
    }

    @Test
    @DisplayName("should parse all valid status strings")
    void shouldParseAllValidStatusStrings() {
      assertEquals(RequestStatus.REJECTED, RequestStatus.fromString("rejected"));
      assertEquals(RequestStatus.GRANTED, RequestStatus.fromString("granted"));
      assertEquals(RequestStatus.WITHDRAWN, RequestStatus.fromString("withdrawn"));
    }

    @Test
    @DisplayName("should throw BaseDxException for invalid status string")
    void shouldThrowForInvalidStatusString() {
      assertThrows(BaseDxException.class, () -> RequestStatus.fromString("invalid"));
    }

    @Test
    @DisplayName("should throw BaseDxException for empty string")
    void shouldThrowForEmptyString() {
      assertThrows(BaseDxException.class, () -> RequestStatus.fromString(""));
    }
  }

  @Nested
  @DisplayName("RequestType Tests")
  class RequestTypeTests {

    @Test
    @DisplayName("DOWNLOAD should have correct request string")
    void downloadShouldHaveCorrectRequestString() {
      assertEquals("download", RequestType.DOWNLOAD.getRequest());
    }

    @Test
    @DisplayName("RequestType should have one value")
    void requestTypeShouldHaveOneValue() {
      assertEquals(1, RequestType.values().length);
    }
  }
}
