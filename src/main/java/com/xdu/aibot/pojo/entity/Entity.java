package com.xdu.aibot.pojo.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 实体节点
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Entity {
    private String name;
    private String type;
    private String description;
}