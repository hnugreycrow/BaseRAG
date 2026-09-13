package com.hnu.backend.document.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hnu.backend.document.domain.DocumentVersion;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DocumentVersionMapper extends BaseMapper<DocumentVersion> {}
