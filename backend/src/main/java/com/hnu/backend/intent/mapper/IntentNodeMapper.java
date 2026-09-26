package com.hnu.backend.intent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hnu.backend.intent.entity.IntentNodeEntity;
import org.apache.ibatis.annotations.Mapper;

/** 意图节点标准 CRUD 映射器。 */
@Mapper
public interface IntentNodeMapper extends BaseMapper<IntentNodeEntity> {}
