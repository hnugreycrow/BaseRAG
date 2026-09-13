package com.hnu.backend.shared.web;

import java.util.List;

/** 分页查询的统一响应结构，页码从 1 开始。 */
public record PageResponse<T>(List<T> items, long total, int page, int pageSize, int totalPages) {

  public static <T> PageResponse<T> of(List<T> items, long total, int page, int pageSize) {
    long pages = total == 0 ? 0 : (total + pageSize - 1) / pageSize;
    return new PageResponse<>(
        List.copyOf(items), total, page, pageSize, (int) Math.min(pages, Integer.MAX_VALUE));
  }

  public static <T> PageResponse<T> empty(int page, int pageSize) {
    return of(List.of(), 0, page, pageSize);
  }
}
