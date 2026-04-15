package com.xdu.aibot.pojo.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Relationship {
    private String source;
    private String target;
    private String name;
    private String type;
    private String description;
    private int strength;
}
