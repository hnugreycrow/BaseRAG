package com.hnu.backend.observability.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hnu.backend.observability.entity.RagStageRun;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 问答阶段记录的批量写入和瀑布查询接口。 */
@Mapper
public interface RagStageRunMapper extends BaseMapper<RagStageRun> {
  /**
   * 在 run 终态事务中一次性插入全部阶段。
   *
   * @param stages 阶段实体
   * @return 插入数量
   */
  int insertBatch(@Param("stages") List<RagStageRun> stages);

  /**
   * 按开始顺序加载阶段瀑布。
   *
   * @param runId 运行标识
   * @return 阶段列表
   */
  default List<RagStageRun> listByRun(UUID runId) {
    return selectList(
        Wrappers.<RagStageRun>lambdaQuery()
            .eq(RagStageRun::getRagRunId, runId)
            .orderByAsc(RagStageRun::getSequenceNo));
  }
}
