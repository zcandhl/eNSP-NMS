package com.ensp.nms.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 登录请求入参对象。
 *
 * @author  ZC
 * @version 1.0.0
 * @since   17
 * @date    2026-08-15
 */

@Data
public class LoginRequest {

    @NotBlank
    private String username;

    @NotBlank
    private String password;
}
