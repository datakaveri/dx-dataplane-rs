package org.cdpg.dx.common.util;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("PaginationInfo Tests")
class PaginationInfoTest {

  @Nested
  @DisplayName("Constructor Tests")
  class ConstructorTests {

    @Test
    @DisplayName("should store all constructor parameters correctly")
    void shouldStoreAllConstructorParameters() {
      PaginationInfo info = new PaginationInfo(1, 10, 100, 10, true, false);
      assertEquals(1, info.getPage());
      assertEquals(10, info.getSize());
      assertEquals(100, info.getTotalCount());
      assertEquals(10, info.getTotalPages());
      assertTrue(info.isHasNext());
      assertFalse(info.isHasPrevious());
    }

    @Test
    @DisplayName("should handle first page correctly")
    void shouldHandleFirstPageCorrectly() {
      PaginationInfo info = new PaginationInfo(0, 20, 50, 3, true, false);
      assertEquals(0, info.getPage());
      assertTrue(info.isHasNext());
      assertFalse(info.isHasPrevious());
    }

    @Test
    @DisplayName("should handle last page correctly")
    void shouldHandleLastPageCorrectly() {
      PaginationInfo info = new PaginationInfo(4, 10, 50, 5, false, true);
      assertEquals(4, info.getPage());
      assertFalse(info.isHasNext());
      assertTrue(info.isHasPrevious());
    }
  }

  @Nested
  @DisplayName("Setter Tests")
  class SetterTests {

    @Test
    @DisplayName("setters should update all fields")
    void settersShouldUpdateAllFields() {
      PaginationInfo info = new PaginationInfo(0, 0, 0, 0, false, false);
      info.setPage(5);
      info.setSize(25);
      info.setTotalCount(500);
      info.setTotalPages(20);
      info.setHasNext(true);
      info.setHasPrevious(true);

      assertEquals(5, info.getPage());
      assertEquals(25, info.getSize());
      assertEquals(500, info.getTotalCount());
      assertEquals(20, info.getTotalPages());
      assertTrue(info.isHasNext());
      assertTrue(info.isHasPrevious());
    }
  }
}
