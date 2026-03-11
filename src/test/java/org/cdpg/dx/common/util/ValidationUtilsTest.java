package org.cdpg.dx.common.util;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ValidationUtils Tests")
class ValidationUtilsTest {

  @Nested
  @DisplayName("requireNonNullOrBlank Tests")
  class RequireNonNullOrBlankTests {

    @Test
    @DisplayName("should return value when non-null and non-blank")
    void shouldReturnValueWhenValid() {
      String result = ValidationUtils.requireNonNullOrBlank("hello", "testField");
      assertEquals("hello", result);
    }

    @Test
    @DisplayName("should throw IllegalArgumentException when value is null")
    void shouldThrowWhenNull() {
      IllegalArgumentException ex =
          assertThrows(
              IllegalArgumentException.class,
              () -> ValidationUtils.requireNonNullOrBlank(null, "myField"));
      assertTrue(ex.getMessage().contains("myField"));
      assertTrue(ex.getMessage().contains("Missing or blank"));
    }

    @Test
    @DisplayName("should throw IllegalArgumentException when value is blank")
    void shouldThrowWhenBlank() {
      IllegalArgumentException ex =
          assertThrows(
              IllegalArgumentException.class,
              () -> ValidationUtils.requireNonNullOrBlank("   ", "myField"));
      assertTrue(ex.getMessage().contains("myField"));
    }

    @Test
    @DisplayName("should throw IllegalArgumentException when value is empty string")
    void shouldThrowWhenEmpty() {
      assertThrows(
          IllegalArgumentException.class,
          () -> ValidationUtils.requireNonNullOrBlank("", "field"));
    }
  }

  @Nested
  @DisplayName("requireNonNull (generic) Tests")
  class RequireNonNullGenericTests {

    @Test
    @DisplayName("should return object when non-null")
    void shouldReturnObjectWhenNonNull() {
      Object obj = new Object();
      Object result = ValidationUtils.requireNonNull(obj, "testObj");
      assertSame(obj, result);
    }

    @Test
    @DisplayName("should throw IllegalArgumentException when object is null")
    void shouldThrowWhenObjectIsNull() {
      IllegalArgumentException ex =
          assertThrows(
              IllegalArgumentException.class,
              () -> ValidationUtils.requireNonNull((Object) null, "myObj"));
      assertTrue(ex.getMessage().contains("Missing required field"));
      assertTrue(ex.getMessage().contains("myObj"));
    }
  }

  @Nested
  @DisplayName("requireNonNull (Integer) Tests")
  class RequireNonNullIntegerTests {

    @Test
    @DisplayName("should return integer when non-null")
    void shouldReturnIntegerWhenNonNull() {
      Integer val = 42;
      Integer result = ValidationUtils.requireNonNull(val, "count");
      assertEquals(42, result);
    }

    @Test
    @DisplayName("should throw IllegalArgumentException when integer is null")
    void shouldThrowWhenIntegerIsNull() {
      IllegalArgumentException ex =
          assertThrows(
              IllegalArgumentException.class,
              () -> ValidationUtils.requireNonNull((Integer) null, "count"));
      assertTrue(ex.getMessage().contains("Missing required integer"));
      assertTrue(ex.getMessage().contains("count"));
    }
  }

  @Nested
  @DisplayName("requireNonNull (UUID) Tests")
  class RequireNonNullUuidTests {

    @Test
    @DisplayName("should return UUID when non-null")
    void shouldReturnUuidWhenNonNull() {
      UUID uuid = UUID.randomUUID();
      UUID result = ValidationUtils.requireNonNull(uuid, "userId");
      assertSame(uuid, result);
    }

    @Test
    @DisplayName("should throw IllegalArgumentException when UUID is null")
    void shouldThrowWhenUuidIsNull() {
      IllegalArgumentException ex =
          assertThrows(
              IllegalArgumentException.class,
              () -> ValidationUtils.requireNonNull((UUID) null, "userId"));
      assertTrue(ex.getMessage().contains("Missing required UUID"));
      assertTrue(ex.getMessage().contains("userId"));
    }
  }

  @Nested
  @DisplayName("requireTrue Tests")
  class RequireTrueTests {

    @Test
    @DisplayName("should return true when condition is true")
    void shouldReturnTrueWhenConditionIsTrue() {
      boolean result = ValidationUtils.requireTrue(true, "should not fail");
      assertTrue(result);
    }

    @Test
    @DisplayName("should throw IllegalArgumentException when condition is false")
    void shouldThrowWhenConditionIsFalse() {
      IllegalArgumentException ex =
          assertThrows(
              IllegalArgumentException.class,
              () -> ValidationUtils.requireTrue(false, "condition violated"));
      assertEquals("condition violated", ex.getMessage());
    }
  }
}
