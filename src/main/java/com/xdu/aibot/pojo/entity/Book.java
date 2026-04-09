package com.xdu.aibot.pojo.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import java.io.Serializable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * <p>
 * 书籍表
 * </p>
 *
 * @author k
 * @since 2026-02-13
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("book")
public class Book implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /**
     * 书籍名称
     */
    private String name;

    /**
     * 作者姓名
     */
    private String author;

    /**
     * 书籍类型：亚洲文学、欧美文学、诗歌、科幻、历史、其它
     */
    private String type;

    /**
     * 书籍评分1-10
     */
    private Integer score;

    /**
     * 库存
     */
    private Integer stock;
}
