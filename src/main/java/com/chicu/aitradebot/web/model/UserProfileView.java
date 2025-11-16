package com.chicu.aitradebot.web.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UserProfileView {
    private String username;
    private Long chatId;
    private String exchange;
    private String email;
}
