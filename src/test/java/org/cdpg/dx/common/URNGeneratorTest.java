package org.cdpg.dx.common;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("URNGenerator Tests")
class URNGeneratorTest {

  @Nested
  @DisplayName("generateUrn Tests")
  class GenerateUrnTests {

    @Test
    @DisplayName("should generate URN by concatenating prefix and identifier")
    void shouldGenerateUrnCorrectly() {
      URNGenerator generator = new URNGenerator("urn:dx:rs:");
      String result = generator.generateUrn("success");
      assertEquals("urn:dx:rs:success", result);
    }

    @Test
    @DisplayName("should handle different prefixes")
    void shouldHandleDifferentPrefixes() {
      URNGenerator generator = new URNGenerator("urn:dx:acl:");
      assertEquals("urn:dx:acl:badRequest", generator.generateUrn("badRequest"));
    }

    @Test
    @DisplayName("getBaseUrnPrefix should return the configured prefix")
    void getBaseUrnPrefixShouldReturnPrefix() {
      URNGenerator generator = new URNGenerator("urn:dx:rs:");
      assertEquals("urn:dx:rs:", generator.getBaseUrnPrefix());
    }

    @Test
    @DisplayName("should handle empty identifier")
    void shouldHandleEmptyIdentifier() {
      URNGenerator generator = new URNGenerator("urn:dx:rs:");
      assertEquals("urn:dx:rs:", generator.generateUrn(""));
    }
  }
}
