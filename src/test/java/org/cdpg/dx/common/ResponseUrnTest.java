package org.cdpg.dx.common;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ResponseUrn Tests")
class ResponseUrnTest {

  @Nested
  @DisplayName("Enum Values Tests")
  class EnumValuesTests {

    @Test
    @DisplayName("SUCCESS_URN should have correct urn and message")
    void successUrnShouldHaveCorrectValues() {
      assertEquals("urn:dx:acl:success", ResponseUrn.SUCCESS_URN.getUrn());
      assertEquals("Success", ResponseUrn.SUCCESS_URN.getMessage());
    }

    @Test
    @DisplayName("RESOURCE_NOT_FOUND_URN should have correct values")
    void resourceNotFoundUrnShouldHaveCorrectValues() {
      assertEquals("urn:dx:acl:resourceNotFound", ResponseUrn.RESOURCE_NOT_FOUND_URN.getUrn());
      assertEquals(
          "Document of given id does not exist", ResponseUrn.RESOURCE_NOT_FOUND_URN.getMessage());
    }

    @Test
    @DisplayName("INTERNAL_SERVER_ERROR should have correct values")
    void internalServerErrorShouldHaveCorrectValues() {
      assertEquals("urn:dx:acl:internalServerError", ResponseUrn.INTERNAL_SERVER_ERROR.getUrn());
      assertEquals("Internal Server Error", ResponseUrn.INTERNAL_SERVER_ERROR.getMessage());
    }
  }

  @Nested
  @DisplayName("fromCode Tests")
  class FromCodeTests {

    @Test
    @DisplayName("should return correct enum for valid urn code")
    void shouldReturnCorrectEnumForValidCode() {
      ResponseUrn result = ResponseUrn.fromCode("urn:dx:acl:success");
      assertEquals(ResponseUrn.SUCCESS_URN, result);
    }

    @Test
    @DisplayName("should be case-insensitive")
    void shouldBeCaseInsensitive() {
      ResponseUrn result = ResponseUrn.fromCode("URN:DX:ACL:SUCCESS");
      assertEquals(ResponseUrn.SUCCESS_URN, result);
    }

    @Test
    @DisplayName("should return NOT_YET_IMPLEMENTED_URN for unknown code")
    void shouldReturnNotYetImplementedForUnknownCode() {
      ResponseUrn result = ResponseUrn.fromCode("urn:unknown:code");
      assertEquals(ResponseUrn.NOT_YET_IMPLEMENTED_URN, result);
    }
  }

  @Nested
  @DisplayName("toString Tests")
  class ToStringTests {

    @Test
    @DisplayName("toString should format as [urn : message ]")
    void toStringShouldFormatCorrectly() {
      String result = ResponseUrn.SUCCESS_URN.toString();
      assertEquals("[urn:dx:acl:success : Success ]", result);
    }
  }
}
