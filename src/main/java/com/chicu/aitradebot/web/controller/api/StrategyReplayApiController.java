package com.chicu.aitradebot.web.controller.api;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.orchestrator.AiStrategyOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/strategy")
public class StrategyReplayApiController {

    private final AiStrategyOrchestrator orchestrator;

    /**
     * Перерисовать/переотправить уровни/слои (grid lines, TP/SL, зоны и т.п.) в веб-дашборд.
     * Используется после перезагрузки страницы, чтобы клиент сразу получил актуальные слои.
     */
    @PostMapping("/{chatId}/{type}/replay")
    public ResponseEntity<Void> replay(@PathVariable long chatId,
                                       @PathVariable StrategyType type) {
        log.info("🔁 [WEB] replay request: chatId={}, type={}", chatId, type);

        // если у тебя метод называется иначе — подставь свой:
        orchestrator.replayStrategyLayers(chatId, type);

        return ResponseEntity.ok().build();
    }
}
