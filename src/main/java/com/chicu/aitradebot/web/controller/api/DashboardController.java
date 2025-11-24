package com.chicu.aitradebot.web.controller.api;

import com.chicu.aitradebot.web.facade.WebDashboardFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * DashboardController (v4)
 *
 * Абсолютно чистый контроллер.
 * Вся логика вынесена в WebDashboardFacade.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final WebDashboardFacade dashboardFacade;

    @GetMapping
    public Object getDashboard(@RequestParam Long chatId) {
        return dashboardFacade.getDashboard(chatId);
    }
}
