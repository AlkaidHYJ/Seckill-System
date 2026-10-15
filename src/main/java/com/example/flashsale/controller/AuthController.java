package com.example.flashsale.controller;

import com.example.flashsale.common.Result;
import com.example.flashsale.dto.LoginRequest;
import com.example.flashsale.service.AuthService;
import com.example.flashsale.vo.LoginResponse;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return Result.success(authService.login(request.getUsername(), request.getPassword()),
                MDC.get("traceId"));
    }
}