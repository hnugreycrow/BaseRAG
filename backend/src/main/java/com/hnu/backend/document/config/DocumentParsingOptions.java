package com.hnu.backend.document.config;

/**
 * 文档结构解析与打包预算，单位为字符；由配置装配层保留原有配置键。
 *
 * @param chunkSize 目标字符数
 * @param chunkMinSize 最小字符数
 * @param chunkMaxSize 最大字符数
 * @param chunkOverlap 长正文重叠字符数
 */
public record DocumentParsingOptions(
    int chunkSize, int chunkMinSize, int chunkMaxSize, int chunkOverlap) {
  /** 返回目标字符数。 */
  public int getChunkSize() {
    return chunkSize;
  }

  /** 返回最小字符数。 */
  public int getChunkMinSize() {
    return chunkMinSize;
  }

  /** 返回最大字符数。 */
  public int getChunkMaxSize() {
    return chunkMaxSize;
  }

  /** 返回重叠字符数。 */
  public int getChunkOverlap() {
    return chunkOverlap;
  }
}
