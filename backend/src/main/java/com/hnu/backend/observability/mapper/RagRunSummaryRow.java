package com.hnu.backend.observability.mapper;

import lombok.Data;

/** PostgreSQL 聚合得到的问答观测统计。 */
@Data
public class RagRunSummaryRow {
  private long requestCount;
  private long terminalCount;
  private long successCount;
  private long degradedCount;
  private Long totalP50Ms;
  private Long totalP95Ms;
  private Long endToEndTtftP50Ms;
  private Long endToEndTtftP95Ms;
  private Long modelTtftP50Ms;
  private Long modelTtftP95Ms;
}
