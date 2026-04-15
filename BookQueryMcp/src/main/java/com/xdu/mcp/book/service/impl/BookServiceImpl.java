package com.xdu.mcp.book.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xdu.mcp.book.entity.Book;
import com.xdu.mcp.book.mapper.BookMapper;
import com.xdu.mcp.book.service.IBookService;
import org.springframework.stereotype.Service;

@Service
public class BookServiceImpl extends ServiceImpl<BookMapper, Book> implements IBookService {
}
