package com.hnu.backend.conversation.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * 新建用户消息的请求。
 *
 * @param clientMessageId 客户端生成的幂等请求标识
 * @param content 普通消息须非空且不超过 2000 字符；按钮提交可为空，由服务端生成标签
 * @param clarificationId 按钮提交的当前澄清标识，须与节点标识成对提供
 * @param selectedNodeId 服务端允许的 KB 叶子标识
 */
public record MessageRequest(
    @NotNull UUID clientMessageId,
    @Size(max = 2000) String content,
    UUID clarificationId,
    UUID selectedNodeId) {
  /** 兼容普通文本消息。 */
  public MessageRequest(UUID clientMessageId, String content) {
    this(clientMessageId, content, null, null);
  }
}
