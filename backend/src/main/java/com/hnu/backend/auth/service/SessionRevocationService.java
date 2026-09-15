package com.hnu.backend.auth.service;

import cn.dev33.satoken.stp.StpUtil;
import com.hnu.backend.conversation.service.ConversationService;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 统一撤销账号会话，并停止仍可能向旧连接输出内容的活动生成。 */
@Service
public class SessionRevocationService {
  private final ConversationService conversations;

  /**
   * 创建会话撤销服务。
   *
   * @param conversations 会话生成服务
   */
  public SessionRevocationService(ConversationService conversations) {
    this.conversations = conversations;
  }

  /**
   * 停止活动回答并注销指定账号的全部 Sa-Token。
   *
   * @param userId 用户标识
   */
  public void revokeAll(UUID userId) {
    conversations.cancelByOwner(userId);
    StpUtil.logout(userId.toString());
  }
}
