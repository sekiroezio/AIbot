package com.xdu.mcp.book.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("book")
public class Book {
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    private String name;
    private String author;
    private String type;
    private Integer score;
    private Integer stock;
}
