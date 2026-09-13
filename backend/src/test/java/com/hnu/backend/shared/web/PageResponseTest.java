package com.hnu.backend.shared.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class PageResponseTest {
  @Test
  void calculatesTotalPagesAndKeepsOneBasedPageMetadata() {
    PageResponse<String> response = PageResponse.of(List.of("a", "b"), 21, 2, 10);

    assertEquals(List.of("a", "b"), response.items());
    assertEquals(21, response.total());
    assertEquals(2, response.page());
    assertEquals(10, response.pageSize());
    assertEquals(3, response.totalPages());
  }

  @Test
  void emptyPageHasNoTotalPages() {
    PageResponse<String> response = PageResponse.empty(1, 10);

    assertEquals(0, response.total());
    assertEquals(0, response.totalPages());
  }
}
