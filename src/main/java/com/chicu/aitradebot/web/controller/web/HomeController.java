package com.chicu.aitradebot.web.controller.web;

import com.chicu.aitradebot.web.model.DashboardStats;
import com.chicu.aitradebot.web.service.WebDashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@RequiredArgsConstructor
public class HomeController {

    private final WebDashboardService dashboardService;

    @GetMapping("/")
    public String dashboard(Model model) {
        model.addAttribute("active", "dashboard");
        model.addAttribute("chatId", 123456789L);

        DashboardStats stats = dashboardService.getDashboardStats();
        if (stats == null) {
            // 👇 страховка от null
            stats = DashboardStats.builder()
                    .activeStrategies(0)
                    .totalStrategies(0)
                    .totalProfit(0)
                    .avgConfidence(0)
                    .usersCount(0)
                    .build();
        }

        model.addAttribute("stats", stats);
        return "home";
    }
}
