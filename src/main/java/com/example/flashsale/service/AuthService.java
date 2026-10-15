package com.example.flashsale.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.flashsale.common.ErrorCode;
import com.example.flashsale.config.JwtProperties;
import com.example.flashsale.entity.User;
import com.example.flashsale.exception.BizException;
import com.example.flashsale.mapper.UserMapper;
import com.example.flashsale.util.JwtUtil;
import com.example.flashsale.vo.LoginResponse;
import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserMapper userMapper;
    private final JwtUtil jwtUtil;
    private final JwtProperties jwtProperties;

    public AuthService(UserMapper userMapper, JwtUtil jwtUtil, JwtProperties jwtProperties) {
        this.userMapper = userMapper;
        this.jwtUtil = jwtUtil;
        this.jwtProperties = jwtProperties;
    }

    public LoginResponse login(String username, String password) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username)
                .last("LIMIT 1"));

        if (user == null || !BCrypt.checkpw(password, user.getPassword())) {
            log.warn("Login failed, username={}, traceId={}", username, traceId());
            throw new BizException(ErrorCode.MISSING_PARAMETER, "用户名或密码错误");
        }
        if (!Integer.valueOf(1).equals(user.getStatus())) {
            log.warn("Login rejected for inactive user, userId={}, traceId={}",
                    user.getId(), traceId());
            throw new BizException(ErrorCode.FORBIDDEN);
        }

        String accessToken = jwtUtil.generateToken(user.getId(), user.getUsername());
        log.info("Login succeeded, userId={}, traceId={}", user.getId(), traceId());
        return new LoginResponse(accessToken, "Bearer", jwtProperties.getExpire(),
                user.getId(), user.getUsername());
    }

    private String traceId() {
        return MDC.get("traceId");
    }
}