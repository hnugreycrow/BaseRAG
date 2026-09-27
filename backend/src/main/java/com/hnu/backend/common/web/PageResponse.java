package com.hnu.backend.common.web;

import java.util.List;

/**
 * 分页查询的统一响应结构，页码从 1 开始，空结果的总页数为 0。
 *
 * @param items 当前页的数据
 * @param total 全部匹配项数量
 * @param page 从 1 开始的当前页码
 * @param pageSize 每页请求数量
 * @param totalPages 向上取整的总页数
 */
public record PageResponse<T>(List<T> items, long total, int page, int pageSize, int totalPages) {

  /**
   * 复制当前页数据并计算总页数；总页数超过整数上限时截断为整数上限。
   *
   * @param items 当前页的数据
   * @param total 全部匹配项数量
   * @param page 从 1 开始的当前页码
   * @param pageSize 每页请求数量，必须大于 0
   * @return 不再受原列表后续修改影响的分页结果
   */
  public static <T> PageResponse<T> of(List<T> items, long total, int page, int pageSize) {
    long pages = total == 0 ? 0 : (total + pageSize - 1) / pageSize;
    return new PageResponse<>(
        List.copyOf(items), total, page, pageSize, (int) Math.min(pages, Integer.MAX_VALUE));
  }

  /**
   * 创建保留请求页码与分页大小的空结果。
   *
   * @param page 从 1 开始的当前页码
   * @param pageSize 每页请求数量，必须大于 0
   * @return 总数及总页数均为 0 的分页结果
   */
  public static <T> PageResponse<T> empty(int page, int pageSize) {
    return of(List.of(), 0, page, pageSize);
  }
}
