package com.xdu.aibot.service.impl;

import com.xdu.aibot.pojo.entity.Book;
import com.xdu.aibot.mapper.BookMapper;
import com.xdu.aibot.service.IBookService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 学科表 服务实现类
 * </p>
 *
 * @author k
 * @since 2026-02-13
 */
@Service
public class BookServiceImpl extends ServiceImpl<BookMapper, Book> implements IBookService {

}
