package com.hnu.backend.intent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hnu.backend.intent.entity.IntentBindingEntity;
import org.apache.ibatis.annotations.Mapper;

/** 叶子知识库绑定标准 CRUD 映射器。 */
@Mapper
public interface IntentBindingMapper extends BaseMapper<IntentBindingEntity> {}
