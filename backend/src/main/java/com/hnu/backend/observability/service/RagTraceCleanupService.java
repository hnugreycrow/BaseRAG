package com.hnu.backend.observability.service;

import com.hnu.backend.observability.configuration.ObservabilityProperties;
import com.hnu.backend.observability.mapper.RagRunMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 每日删除超过保留期的问答 Trace。 */
@Service
@Profile("local")
public class RagTraceCleanupService {
  private static final Logger log = LoggerFactory.getLogger(RagTraceCleanupService.class);
  private final RagRunMapper ragRunMapper;
  private final ObservabilityProperties config;

  /**
   * 创建清理服务。
   *
   * @param ragRunMapper 运行数据接口
   * @param config 保留配置
   */
  public RagTraceCleanupService(RagRunMapper ragRunMapper, ObservabilityProperties config) {
    this.ragRunMapper = ragRunMapper;
    this.config = config;
  }

  /** 按 UTC 日程删除过期运行和级联阶段记录。 */
  @Scheduled(cron = "${observability.cleanup-cron:0 15 3 * * *}", zone = "UTC")
  public void deleteExpired() {
    int deleted = ragRunMapper.deleteExpired(config.getRetentionDays());
    if (deleted > 0) {
      log.info("Deleted expired RAG traces count={}", deleted);
    }
  }
}
