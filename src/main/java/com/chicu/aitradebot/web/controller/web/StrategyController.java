package com.chicu.aitradebot.web.controller.web;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.service.UserProfileService;
import com.chicu.aitradebot.strategy.smartfusion.SmartFusionStrategySettingsService; // <-- интерфейс
import com.chicu.aitradebot.web.model.StrategyViewModel;
import com.chicu.aitradebot.web.service.StrategyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 🌐 Контроллер управления стратегиями через веб-интерфейс.
 * /strategies — список
 * /strategies/{name} — дашборд (если понадобится)
 * /strategies/{name}/settings — страница настроек
 * /strategies/{id}/config — совместимость с маршрутом по id
 */
@Controller
@RequestMapping("/strategies")
@RequiredArgsConstructor
@Slf4j
public class StrategyController {

    private final StrategyService strategyService;
    private final SmartFusionStrategySettingsService smartFusionSettingsService; // <-- интерфейс
    private final UserProfileService userProfileService;

    /**
     * 📋 Список всех стратегий.
     */
    @GetMapping
    public String strategies(Model model) {
        Long chatId = safeCurrentChatId();
        log.debug("📋 Отображение стратегий для chatId={}", chatId);

        model.addAttribute("active", "strategies");
        model.addAttribute("pageTitle", "AI Trading — Стратегии");
        model.addAttribute("strategies", strategyService.getAllView());
        model.addAttribute("chatId", chatId); // нужно для кнопок/JS в шаблоне
        return "strategies";
    }

    /**
     * ⚙️ Универсальный маршрут настроек.
     * Поддерживает:
     *  - /strategies/{id}/config
     *  - /strategies/{name}/settings
     */
    @GetMapping({"/{id}/config", "/{name}/settings"})
    public String config(@PathVariable(value = "id", required = false) String id,
                         @PathVariable(value = "name", required = false) String name,
                         @RequestParam(value = "chatId", required = false) Long chatIdParam,
                         @RequestParam(value = "symbol", required = false) String symbolParam,
                         Model model) {
        try {
            StrategyViewModel strategyVm;

            // Определяем: пришёл id (число) или имя
            if (id != null && id.matches("\\d+")) {
                Long parsedId = Long.parseLong(id);
                strategyVm = strategyService.getByIdView(parsedId);
            } else if (name != null && !name.isBlank()) {
                strategyVm = strategyService.getByName(name);
            } else {
                throw new IllegalArgumentException("Стратегия не указана");
            }

            if (strategyVm == null) {
                throw new IllegalArgumentException("Стратегия не найдена");
            }

            Long chatId = (chatIdParam != null) ? chatIdParam : safeCurrentChatId();
            String symbol = (symbolParam != null && !symbolParam.isBlank())
                    ? symbolParam
                    : (strategyVm.getSymbol() != null ? strategyVm.getSymbol() : "BTCUSDT");

            // Определяем тип стратегии (предпочтительно по machine-значению, а не по человеко читаемому имени)
            StrategyType type = resolveType(strategyVm);

            // Подгружаем настройки по типу
            Object settings;
            if (type == StrategyType.SMART_FUSION) {
                settings = smartFusionSettingsService.getOrCreate(chatId, symbol);
            } else {
                // 🔸 Защита от null — создаём "пустые" настройки для других стратегий
                settings = Map.of("symbol", symbol, "placeholder", true);
            }
            model.addAttribute("settings", settings);

            model.addAttribute("strategy", strategyVm);
            model.addAttribute("chatId", chatId);
            model.addAttribute("pageTitle", "Настройка стратегии — " + strategyVm.getStrategyName());
            model.addAttribute("active", "strategies");

            return "strategy-config";

        } catch (Exception e) {
            log.error("❌ Ошибка при загрузке конфигурации стратегии: {}", e.getMessage(), e);
            model.addAttribute("error", e.getMessage());
            model.addAttribute("pageTitle", "Ошибка — стратегия не найдена");
            return "error";
        }
    }

    // --------- helpers ---------

    private Long safeCurrentChatId() {
        try {
            Long chatId = userProfileService.getCurrentChatId();
            return (chatId != null && chatId > 0) ? chatId : 1L;
        } catch (Exception e) {
            log.warn("⚠️ Не удалось получить текущий chatId: {}", e.getMessage());
            return 1L;
        }
    }

    private StrategyType resolveType(StrategyViewModel vm) {
        // Пытаемся сначала взять машинное имя типа (если оно есть в модели)
        if (vm.getStrategyType() != null) {
            try {
                return StrategyType.valueOf(vm.getStrategyType().trim().toUpperCase());
            } catch (Exception ignored) {
            }
        }
        // Фоллбэк: пробуем по отображаемому имени
        if (vm.getStrategyName() != null) {
            try {
                String normalized = vm.getStrategyName().trim().replace(' ', '_').toUpperCase();
                return StrategyType.valueOf(normalized);
            } catch (Exception ignored) {
            }
        }
        log.warn("⚠️ Не удалось определить StrategyType для VM: {}", vm);
        return null;
    }
}
