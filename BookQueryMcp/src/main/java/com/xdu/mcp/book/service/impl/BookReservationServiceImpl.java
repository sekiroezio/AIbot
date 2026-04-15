package com.xdu.mcp.book.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xdu.mcp.book.entity.BookReservation;
import com.xdu.mcp.book.mapper.BookReservationMapper;
import com.xdu.mcp.book.service.IBookReservationService;
import org.springframework.stereotype.Service;

@Service
public class BookReservationServiceImpl extends ServiceImpl<BookReservationMapper, BookReservation> implements IBookReservationService {
}
