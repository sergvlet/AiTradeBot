package com.chicu.aitradebot.account;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Value
@Builder
public class AccountBalanceSnapshot {

    /** Активы, доступные для выбора (free + locked > 0) */
    List<String> availableAssets;

    /** Выбранный актив (из StrategySettings.accountAsset, либо авто-выбор) */
    String selectedAsset;

    /** Баланс выбранного актива (для UI: free/locked/total) */
    AssetBalance selectedBalance;

    /**
     * Полная карта балансов аккаунта по активам.
     * Нужна для SELL по base-активу: BTC из BTCUSDT, ETH из ETHUSDT и т.д.
     */
    Map<String, AssetBalance> balances;

    /** True если успешно получили баланс с биржи */
    boolean connectionOk;

    /** Опционально: текст ошибки, чтобы красиво выводить в UI/логах */
    String error;

    /** Backward-compat для старого кода */
    public AssetBalance getBalance(String asset) {
        if (asset == null) return null;
        return getBalances().get(normalize(asset));
    }

    /** Backward-compat для старого кода */
    public AssetBalance getAssetBalance(String asset) {
        return getBalance(asset);
    }

    /** Backward-compat для reflection в TradeExecutionServiceImpl */
    public Map<String, AssetBalance> getBalances() {
        if (balances == null || balances.isEmpty()) {
            return Collections.emptyMap();
        }
        return balances;
    }

    /** Backward-compat для reflection в TradeExecutionServiceImpl */
    public Map<String, AssetBalance> getFullBalance() {
        return getBalances();
    }

    private static String normalize(String s) {
        if (s == null) return null;
        String t = s.trim().toUpperCase(Locale.ROOT);
        return t.isEmpty() ? null : t;
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

        public BigDecimal getTotal() {
            return getTotalSafe();
        }

        private static BigDecimal nz(BigDecimal v) {
            return v != null ? v : BigDecimal.ZERO;
        }
    }
}