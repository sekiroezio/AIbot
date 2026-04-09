package com.xdu.aibot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xdu.aibot.pojo.entity.ChatMessage;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {
}
