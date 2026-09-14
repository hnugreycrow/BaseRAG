package com.hnu.backend.document.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hnu.backend.document.entity.DocumentVersion;
import org.apache.ibatis.annotations.Mapper;

/** 文档版本的 MyBatis-Plus 数据访问接口。 */
@Mapper
public interface DocumentVersionMapper extends BaseMapper<DocumentVersion> {}
