package com.chicu.aitradebot.web.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ApiResponse {
    private String status;
    private String message;

    public static ApiResponse ok(String msg) {
        return new ApiResponse("ok", msg);
    }
}
