package com.hnu.backend.rag.retrieval;

import com.hnu.backend.rag.pipeline.RagBudgetSnapshot;
import java.util.List;

/** 对有序证据去重，保留来源归属和稳定候选标识。 */
public interface EvidenceDeduplicator {
  /** 执行该能力，保持输入顺序及既有失败语义。 */
  DeduplicationResult execute(List<EvidenceCandidate> input, RagBudgetSnapshot budget);
}
