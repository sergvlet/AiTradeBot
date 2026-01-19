package com.chicu.aitradebot.ai.tuning.candidates;

import com.chicu.aitradebot.ai.tuning.TuningCandidate;
import com.chicu.aitradebot.ai.tuning.guard.GuardDecision;
import com.chicu.aitradebot.ai.tuning.guard.TuningGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class CandidateFilter {

    public List<TuningCandidate> filter(Long chatId,
                                        Map<String, Object> currentParams,
                                        List<TuningCandidate> candidates,
                                        TuningGuard guard) {

        if (candidates == null || candidates.isEmpty()) return List.of();

        List<TuningCandidate> ok = new ArrayList<>(candidates.size());
        int denied = 0;

        for (TuningCandidate c : candidates) {
            GuardDecision d = guard.checkCandidate(chatId, currentParams, c);
            if (d.allowed()) {
                ok.add(c);
            } else {
                denied++;
            }
        }

        log.info("🧱 Guard filter: input={}, ok={}, denied={}", candidates.size(), ok.size(), denied);
        return ok;
    }
}
