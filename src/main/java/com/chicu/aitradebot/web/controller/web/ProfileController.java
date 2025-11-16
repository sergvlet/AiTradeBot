package com.chicu.aitradebot.web.controller.web;

import com.chicu.aitradebot.web.service.WebDashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@RequiredArgsConstructor
public class ProfileController {

    private final WebDashboardService dashboardService;

    @GetMapping("/profile")
    public String profile(Model model) {
        model.addAttribute("pageTitle", "AI Trading — Профиль пользователя");
        model.addAttribute("profile", dashboardService.getUserProfile());
        return "profile";
    }
}
