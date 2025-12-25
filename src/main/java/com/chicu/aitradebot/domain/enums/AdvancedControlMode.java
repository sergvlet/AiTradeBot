package com.chicu.aitradebot.domain.enums;

/**
 * Режим управления продвинутыми параметрами:
 * - MANUAL: пользователь задаёт руками
 * - AI: параметры меняет ИИ/обучение
 * - HYBRID: ИИ предлагает, пользователь подтверждает/ограничивает
 */
public enum AdvancedControlMode {
    MANUAL,
    AI,
    HYBRID
}
