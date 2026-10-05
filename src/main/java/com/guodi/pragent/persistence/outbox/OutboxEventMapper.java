package com.guodi.pragent.persistence.outbox;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

@Mapper
public interface OutboxEventMapper extends BaseMapper<OutboxEventEntity> {
}
