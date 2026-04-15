package com.xdu.mcp.book.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("book_reservation")
public class BookReservation {
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    private String bookName;
    private String readerName;
    private String phone;
    private String remark;
}
