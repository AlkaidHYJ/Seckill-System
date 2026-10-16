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
            // 未携带凭证：未认证 401（FORBIDDEN/403 仅保留给"已认证但无权"，见 REST_API.md §1）
            throw new BizException(ErrorCode.UNAUTHORIZED, "缺少 Authorization: Bearer <accessToken> 请求头");
        }

        try {
            Claims claims = jwtUtil.parseToken(authorization.substring(7));
            UserContext.setUserId(Long.valueOf(claims.getSubject()));
            return true;
        } catch (JwtException | IllegalArgumentException exception) {
            // 凭证非法/过期/载荷不合法：仍属未认证 401
            throw new BizException(ErrorCode.UNAUTHORIZED, "访问令牌无效或已过期，请重新登录");
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception exception) {
        UserContext.clear();
    }
}