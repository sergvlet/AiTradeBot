package com.chicu.aitradebot.domain.enums;

/**
 * Режим ML-гейта.
 * OFF     — отключен
 * SHADOW  — считаем/логируем, но не блокируем
 * ENFORCE — блокируем вход при плохом прогнозе
 */
public enum MlGateMode {
    OFF,
    SHADOW,
    ENFORCE
}
