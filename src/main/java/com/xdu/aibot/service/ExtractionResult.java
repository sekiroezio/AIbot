package com.xdu.aibot.service;

import com.xdu.aibot.pojo.entity.Entity;
import com.xdu.aibot.pojo.entity.Relationship;
import lombok.Data;

import java.util.List;

@Data
public class ExtractionResult {
    private List<Entity> entities;
    private List<Relationship> relationships;

    public ExtractionResult() {
        this.entities = List.of();
        this.relationships = List.of();
    }

    public ExtractionResult(List<Entity> entities, List<Relationship> relationships) {
        this.entities = entities;
        this.relationships = relationships;
    }
}
