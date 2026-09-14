package com.hnu.backend.document.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hnu.backend.document.entity.Document;
import org.apache.ibatis.annotations.Mapper;

/** 文档逻辑实体的 MyBatis-Plus 数据访问接口。 */
@Mapper
public interface DocumentMapper extends BaseMapper<Document> {}
