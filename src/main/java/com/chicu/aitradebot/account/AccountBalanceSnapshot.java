package com.chicu.aitradebot.account;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.util.List;

@Value
@Builder
public class AccountBalanceSnapshot {

    /** Активы, доступные для выбора (free + locked > 0) */
    List<String> availableAssets;

    /** Выбранный актив (из StrategySettings.accountAsset, либо авто-выбор) */
    String selectedAsset;

    /** Баланс выбранного актива (для UI: free/locked/total) */
    AssetBalance selectedBalance;

    /** true если успешно получили баланс с биржи */
    boolean connectionOk;

    /** опционально: текст ошибки, чтобы красиво выводить в UI/логах */
    String error;

    /**
     * ✅ Backward-compat: старый код мог ожидать именно это имя.
     * Возвращаем НЕ null (чтобы нигде не падало на .compareTo/.signum).
     */
    public BigDecimal getSelectedFreeBalance() {
        return selectedBalance != null ? selectedBalance.getFreeSafe() : BigDecimal.ZERO;
    }

    /**
     * Часто удобно для UI/логики.
     */
    public BigDecimal getSelectedLockedBalance() {
        return selectedBalance != null ? selectedBalance.getLockedSafe() : BigDecimal.ZERO;
    }

    public BigDecimal getSelectedTotalBalance() {
        return selectedBalance != null ? selectedBalance.getTotalSafe() : BigDecimal.ZERO;
    }

    /**
     * Упрощённые флаги для UI.
     */
    public boolean hasAnyAssets() {
        return availableAssets != null && !availableAssets.isEmpty();
    }

    public boolean hasSelectedBalance() {
        return selectedBalance != null && selectedBalance.getTotalSafe().signum() > 0;
    }

    @Value
    @Builder
    public static class AssetBalance {
        BigDecimal free;
        BigDecimal locked;

        public static AssetBalance of(BigDecimal free, BigDecimal locked) {
            return AssetBalance.builder()
                    .free(nz(free))
                    .locked(nz(locked))
                    .build();
        }

        public BigDecimal getFreeSafe() {
            return nz(free);
        }

        public BigDecimal getLockedSafe() {
            return nz(locked);
        }

        public BigDecimal getTotalSafe() {
            return nz(free).add(nz(locked));
        }

        // Backward-compat: если где-то уже дергают getTotal()
        public BigDecimal getTotal() {
            return getTotalSafe();
        }

        private static BigDecimal nz(BigDecimal v) {
            return v != null ? v : BigDecimal.ZERO;
        }
    }
}