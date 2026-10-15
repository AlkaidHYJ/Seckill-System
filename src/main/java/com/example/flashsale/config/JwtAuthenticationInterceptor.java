package com.example.flashsale.config;

import com.example.flashsale.common.ErrorCode;
import com.example.flashsale.common.UserContext;
import com.example.flashsale.exception.BizException;
import com.example.flashsale.util.JwtUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

public class JwtAuthenticationInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;

    public JwtAuthenticationInterceptor(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }

        try {
            Claims claims = jwtUtil.parseToken(authorization.substring(7));
            UserContext.setUserId(Long.valueOf(claims.getSubject()));
            return true;
        } catch (JwtException | IllegalArgumentException exception) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception exception) {
        UserContext.clear();
    }
}