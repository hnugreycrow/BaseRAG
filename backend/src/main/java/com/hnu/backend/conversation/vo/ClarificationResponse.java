package com.hnu.backend.conversation.vo;

import com.hnu.backend.rag.api.ClarificationContext.Option;
import java.util.List;
import java.util.UUID;

/**
 * 供消息和会话详情共用的澄清展示元数据。
 *
 * @param id 当前待办标识
 * @param type 固定为 KB_INTENT
 * @param status 当前待办状态，历史消息可能已结束
 * @param options 服务端允许的叶子选择
 * @param question 当前待澄清的子问题
 * @param currentStep 当前项序号，从 1 开始
 * @param totalSteps 本轮待澄清项总数
 */
public record ClarificationResponse(
    UUID id,
    String type,
    String status,
    List<Option> options,
    String question,
    Integer currentStep,
    Integer totalSteps) {}
