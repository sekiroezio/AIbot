package com.xdu.aibot.pojo.query;

import lombok.Data;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;

@Data
public class BookQuery {
    @ToolParam(required = false, description = "书籍类型：亚洲文学、欧美文学、诗歌、科幻、历史、其它")
    private String type;
    @ToolParam(required = false, description = "作者")
    private String author;
    @ToolParam(required = false, description = "书籍名称")
    private String name;
    @ToolParam(required = false, description = "书籍评分：1-10")
    private Integer score;
    @ToolParam(required = false, description = "库存")
    private Integer stock;
    @ToolParam(required = false, description = "排序方式")
    private List<Sort> sorts;

    @Data
    public static class Sort {
        @ToolParam(required = false, description = "排序字段: score或stock")
        private String field;
        @ToolParam(required = false, description = "是否是升序: true/false")
        private Boolean asc;
    }
}