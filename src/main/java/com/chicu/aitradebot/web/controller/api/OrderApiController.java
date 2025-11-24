package com.chicu.aitradebot.web.controller.api;

import com.chicu.aitradebot.web.facade.WebOrderFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/order")
public class OrderApiController {

    private final WebOrderFacade orderFacade;

    @PostMapping("/market/buy")
    public Object marketBuy(
            @RequestParam Long chatId,
            @RequestParam String symbol,
            @RequestParam BigDecimal amount
    ) {
        return orderFacade.marketBuy(chatId, symbol, amount);
    }

    @PostMapping("/market/sell")
    public Object marketSell(
            @RequestParam Long chatId,
            @RequestParam String symbol,
            @RequestParam BigDecimal amount
    ) {
        return orderFacade.marketSell(chatId, symbol, amount);
    }

    @PostMapping("/cancel")
    public Object cancel(
            @RequestParam Long chatId,
            @RequestParam String symbol,
            @RequestParam long orderId
    ) {
        return orderFacade.cancel(chatId, symbol, orderId);
    }

    @GetMapping("/list")
    public Object list(
            @RequestParam Long chatId,
            @RequestParam String symbol
    ) {
        return orderFacade.list(chatId, symbol);
    }
}
