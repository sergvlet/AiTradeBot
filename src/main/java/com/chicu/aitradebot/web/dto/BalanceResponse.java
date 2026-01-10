package com.chicu.aitradebot.web.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class BalanceResponse {
    private String asset;
    private String free;
    private String locked;
    private String total;
}
