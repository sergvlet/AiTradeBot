package com.chicu.aitradebot.web.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DashboardStats {
    private long activeStrategies;
    private long totalStrategies;
    private double totalProfit;
    private double avgConfidence;
    private int usersCount;
}
