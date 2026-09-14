package com.hnu.backend.conversation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hnu.backend.conversation.entity.GenerationAttempt;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface GenerationAttemptMapper extends BaseMapper<GenerationAttempt> {
  /**
   * 更新仍在流式生成的尝试正文检查点。
   *
   * @param id 生成尝试 ID
   * @param content 当前完整正文
   * @return 实际更新行数
   */
  default int checkpoint(UUID id, String content) {
    return update(
        Wrappers.<GenerationAttempt>lambdaUpdate()
            .eq(GenerationAttempt::getId, id)
            .eq(GenerationAttempt::getStatus, "STREAMING")
            .set(GenerationAttempt::getContent, content));
  }

  /**
   * 将流式尝试标记为成功完成。
   *
   * @param id 生成尝试 ID
   * @param content 完整回答正文
   * @param finishReason 供应商返回的结束原因
   * @return 实际更新行数
   */
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

  /**
   * 将仍在流式生成的尝试更新为失败或取消终态。
   *
   * @param id 生成尝试 ID
   * @param status 目标终态
   * @param content 已生成的部分正文
   * @param code 稳定错误码
   * @param message 用户可读错误信息
   * @return 实际更新行数
   */
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

  /**
   * 作废已经完整生成但未通过引用白名单校验的尝试。
   *
   * <p>状态条件严格限制为 {@code COMPLETED}，避免并发取消或其他失败覆盖既有终态。
   *
   * @param id 生成尝试 ID
   * @param code 稳定引用错误码
   * @param message 引用校验失败说明
   * @return 实际更新行数
   */
  default int invalidateCompleted(UUID id, String code, String message) {
    return update(
        Wrappers.<GenerationAttempt>lambdaUpdate()
            .eq(GenerationAttempt::getId, id)
            .eq(GenerationAttempt::getStatus, "COMPLETED")
            .set(GenerationAttempt::getStatus, "FAILED")
            .set(GenerationAttempt::getErrorCode, code)
            .set(GenerationAttempt::getErrorMessage, message)
            .setSql("completed_at = now()"));
  }

  /**
   * 取消指定回答下仍处于流式状态的全部模型尝试。
   *
   * @param assistantMessageId assistant 消息 ID
   * @return 实际更新行数
   */
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

  /**
   * 将应用异常退出时遗留的流式尝试恢复为失败终态。
   *
   * @return 实际恢复行数
   */
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
