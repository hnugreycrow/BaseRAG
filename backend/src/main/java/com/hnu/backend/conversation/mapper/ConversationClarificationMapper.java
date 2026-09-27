package com.hnu.backend.conversation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hnu.backend.conversation.entity.ConversationClarification;
import org.apache.ibatis.annotations.Mapper;

/** KB 澄清记录的基础持久化接口。 */
@Mapper
public interface ConversationClarificationMapper extends BaseMapper<ConversationClarification> {}
