package com.hnu.backend.rag.generation;

/** 为已有证据快照生成引用修复提示词，不重新检索。 */
public interface CitationRepairPrompt {
  /** 返回保持来源白名单的修复提示词。 */
  AssembledPrompt forCitationRepair(AssembledPrompt original);
}
