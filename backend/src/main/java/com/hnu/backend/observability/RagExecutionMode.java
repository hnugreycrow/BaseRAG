package com.hnu.backend.observability;

/** 单次问答采用的主执行路径。 */
public enum RagExecutionMode {
  /** 完整的规划、路由、检索和回答流水线。 */
  FULL_PIPELINE,
  /** 全部子问题均被识别为系统闲聊。 */
  SYSTEM_CHAT,
  /** 为后续性能阶段预留的简单问题快路径。 */
  FAST_PATH
}
