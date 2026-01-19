package com.chicu.aitradebot.market.service;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.market.model.SymbolDescriptor;
import com.chicu.aitradebot.market.model.SymbolListMode;

import java.util.List;

public interface MarketSymbolService {

    List<SymbolDescriptor> getSymbols(
            String exchange,
            NetworkType network,
            String accountAsset,
            SymbolListMode mode
    );

    SymbolDescriptor getSymbolInfo(
            String exchange,
            NetworkType network,
            String accountAsset,
            String symbol
    );
}
