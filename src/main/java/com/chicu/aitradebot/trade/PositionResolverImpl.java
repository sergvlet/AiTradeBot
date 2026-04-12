package com.chicu.aitradebot.trade;

import com.chicu.aitradebot.account.AccountBalanceService;
import com.chicu.aitradebot.account.AccountBalanceSnapshot;
import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategyPositionEntity;
import com.chicu.aitradebot.domain.enums.StrategyPositionStatus;
import com.chicu.aitradebot.exchange.repository.StrategyPositionRepository;
import com.chicu.aitradebot.trade.math.QtyMath;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PositionResolverImpl implements PositionResolver {

    private static final BigDecimal DEFAULT_MIN_TRADABLE_NOTIONAL = new BigDecimal("5.00");

    private final StrategyPositionRepository strategyPositionRepository;
    private final AccountBalanceService accountBalanceService;

    @Override
    public PositionResolution resolve(Long chatId,
                                      StrategyType strategyType,
                                      String exchange,
                                      NetworkType network,
                                      String symbol,
                                      BigDecimal marketPrice) {

        String ex = upper(exchange);
        String sym = upper(symbol);
        if (chatId == null || strategyType == null || ex == null || network == null || sym == null) {
            return new PositionResolution(
                    PositionResolutionState.LOCAL_EXCHANGE_MISMATCH,
                    null,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    DEFAULT_MIN_TRADABLE_NOTIONAL,
                    "bad_args"
            );
        }

        BigDecimal minTradableNotional = DEFAULT_MIN_TRADABLE_NOTIONAL;
        BigDecimal baseQty = resolveFreeBaseQty(chatId, strategyType, ex, network, sym);
        BigDecimal notional = QtyMath.isPositive(baseQty) && QtyMath.isPositive(marketPrice)
                ? baseQty.multiply(marketPrice).setScale(8, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        Optional<StrategyPositionEntity> own = strategyPositionRepository
                .findFirstByChatIdAndStrategyTypeAndExchangeNameAndNetworkTypeAndSymbolAndStatusInOrderByOpenedAtDesc(
                        chatId,
                        strategyType,
                        ex,
                        network,
                        sym,
                        List.of(StrategyPositionStatus.OPEN, StrategyPositionStatus.CLOSING)
                );

        if (own.isPresent()) {
            StrategyPositionEntity pos = own.get();
            if (QtyMath.isPositive(baseQty)) {
                return new PositionResolution(
                        PositionResolutionState.OWN_OPEN_POSITION,
                        pos,
                        baseQty,
                        notional,
                        minTradableNotional,
                        "strategy_position_open"
                );
            }
            return new PositionResolution(
                    PositionResolutionState.LOCAL_EXCHANGE_MISMATCH,
                    pos,
                    baseQty,
                    notional,
                    minTradableNotional,
                    "strategy_position_exists_but_wallet_empty"
            );
        }

        if (!QtyMath.isPositive(baseQty)) {
            return PositionResolution.noPosition(minTradableNotional);
        }

        if (notional.compareTo(minTradableNotional) < 0) {
            return new PositionResolution(
                    PositionResolutionState.DUST_ONLY,
                    null,
                    baseQty,
                    notional,
                    minTradableNotional,
                    "wallet_dust_only"
            );
        }

        return new PositionResolution(
                PositionResolutionState.EXTERNAL_POSITION,
                null,
                baseQty,
                notional,
                minTradableNotional,
                "wallet_position_without_strategy_position"
        );
    }

    private BigDecimal resolveFreeBaseQty(Long chatId,
                                          StrategyType strategyType,
                                          String exchange,
                                          NetworkType network,
                                          String symbol) {
        if (accountBalanceService == null) {
            return BigDecimal.ZERO;
        }
        String baseAsset = guessBaseAsset(symbol);
        if (baseAsset == null) {
            return BigDecimal.ZERO;
        }
        try {
            AccountBalanceSnapshot snap = accountBalanceService.getSnapshot(chatId, strategyType, exchange, network);
            if (snap == null || !snap.isConnectionOk()) {
                return BigDecimal.ZERO;
            }
            AccountBalanceSnapshot.AssetBalance balance = snap.getBalance(baseAsset);
            if (balance == null && snap.getBalances() != null) {
                balance = snap.getBalances().get(baseAsset);
            }
            if (balance == null && snap.getFullBalance() != null) {
                balance = snap.getFullBalance().get(baseAsset);
            }
            return balance != null ? balance.getFreeSafe() : BigDecimal.ZERO;
        } catch (Exception e) {
            log.debug("Не удалось определить base-остаток для PositionResolver chatId={} type={} ex={} net={} sym={} err={}",
                    chatId, strategyType, exchange, network, symbol, e.toString());
            return BigDecimal.ZERO;
        }
    }

    private String guessBaseAsset(String symbol) {
        String s = upper(symbol);
        if (s == null) return null;
        String[] quotes = new String[]{"USDT","USDC","BUSD","FDUSD","TUSD","BTC","ETH","EUR","TRY","BRL","GBP","UAH","PLN"};
        for (String q : quotes) {
            if (s.endsWith(q) && s.length() > q.length()) {
                return s.substring(0, s.length() - q.length());
            }
        }
        return null;
    }

    private static String upper(String v) {
        if (v == null) return null;
        String s = v.trim().toUpperCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }
}
