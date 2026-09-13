package com.hnu.backend.conversation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hnu.backend.conversation.entity.GenerationAttempt;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface GenerationAttemptMapper extends BaseMapper<GenerationAttempt> {
  default int checkpoint(UUID id, String content) {
    return update(
        Wrappers.<GenerationAttempt>lambdaUpdate()
            .eq(GenerationAttempt::getId, id)
            .eq(GenerationAttempt::getStatus, "STREAMING")
            .set(GenerationAttempt::getContent, content));
  }

  default int complete(UUID id, String content, String finishReason) {
    return update(
        Wrappers.<GenerationAttempt>lambdaUpdate()
            .eq(GenerationAttempt::getId, id)
            .eq(GenerationAttempt::getStatus, "STREAMING")
            .set(GenerationAttempt::getStatus, "COMPLETED")
            .set(GenerationAttempt::getContent, content)
            .set(GenerationAttempt::getFinishReason, finishReason)
            .setSql("completed_at = now()"));
  }

  default int fail(UUID id, String status, String content, String code, String message) {
    return update(
        Wrappers.<GenerationAttempt>lambdaUpdate()
            .eq(GenerationAttempt::getId, id)
            .eq(GenerationAttempt::getStatus, "STREAMING")
            .set(GenerationAttempt::getStatus, status)
            .set(GenerationAttempt::getContent, content)
            .set(GenerationAttempt::getErrorCode, code)
            .set(GenerationAttempt::getErrorMessage, message)
            .setSql("completed_at = now()"));
  }

  default int cancelRunning(UUID assistantMessageId) {
    return update(
        Wrappers.<GenerationAttempt>lambdaUpdate()
            .eq(GenerationAttempt::getAssistantMessageId, assistantMessageId)
            .eq(GenerationAttempt::getStatus, "STREAMING")
            .set(GenerationAttempt::getStatus, "CANCELLED")
            .set(GenerationAttempt::getErrorCode, "GENERATION_CANCELLED")
            .set(GenerationAttempt::getErrorMessage, "生成已停止")
            .setSql("completed_at = now()"));
  }

  default int recoverInterrupted() {
    return update(
        Wrappers.<GenerationAttempt>lambdaUpdate()
            .eq(GenerationAttempt::getStatus, "STREAMING")
            .set(GenerationAttempt::getStatus, "FAILED")
            .set(GenerationAttempt::getErrorCode, "GENERATION_INTERRUPTED")
            .set(GenerationAttempt::getErrorMessage, "应用重启中断了生成")
            .setSql("completed_at = now()"));
  }
}
