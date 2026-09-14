package com.hnu.backend.rag.deduplication;

import com.hnu.backend.rag.retrieval.EvidenceCandidate;
import java.util.List;

/**
 * 确定性证据去重结果及各层级的归并数量。
 *
 * @param candidates 去重后的候选，顺序仍按融合分和候选 ID 保持稳定
 * @param exactChunkMerged 因 chunkId 相同而被归并的候选数
 * @param normalizedContentMerged 因规范化正文哈希相同而被归并的候选数
 * @param adjacentOverlapMerged 因同版本相邻分块高度重叠而被归并的候选数
 */
public record DeduplicationResult(
    List<EvidenceCandidate> candidates,
    int exactChunkMerged,
    int normalizedContentMerged,
    int adjacentOverlapMerged) {
  public DeduplicationResult {
    candidates = List.copyOf(candidates);
    if (exactChunkMerged < 0 || normalizedContentMerged < 0 || adjacentOverlapMerged < 0) {
      throw new IllegalArgumentException("Invalid deduplication counters");
    }
  }
}
