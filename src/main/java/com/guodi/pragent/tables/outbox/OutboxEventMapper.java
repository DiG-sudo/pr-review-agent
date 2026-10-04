package com.guodi.pragent.tables.outbox;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

@Mapper
public interface OutboxEventMapper extends BaseMapper<OutboxEventEntity> {
}
