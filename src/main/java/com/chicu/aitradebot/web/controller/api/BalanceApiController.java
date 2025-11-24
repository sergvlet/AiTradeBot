package com.chicu.aitradebot.web.controller.api;

import com.chicu.aitradebot.web.facade.WebBalanceFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/balance")
public class BalanceApiController {

    private final WebBalanceFacade balanceFacade;

    @GetMapping("/total")
    public Object total(@RequestParam Long chatId) {
        return balanceFacade.getTotalBalance(chatId);
    }

    @GetMapping("/assets")
    public Object assets(@RequestParam Long chatId) {
        return balanceFacade.getAssets(chatId);
    }
}
