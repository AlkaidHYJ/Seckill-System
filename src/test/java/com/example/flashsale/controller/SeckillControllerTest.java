package com.example.flashsale.controller;

import com.example.flashsale.common.ErrorCode;
import com.example.flashsale.common.Result;
import com.example.flashsale.common.UserContext;
import com.example.flashsale.entity.Order.OrderStatus;
import com.example.flashsale.exception.BizException;
import com.example.flashsale.service.SeckillService;
import com.example.flashsale.service.SeckillTokenService;
import com.example.flashsale.vo.SeckillResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 可观测（输出 3 seckill.request.total）单元测试：验证 buy 端点按结果码（code tag）分桶计数，
 * 覆盖成功 / 缺 token / 业务异常 / 未分类异常四条路径。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("可观测 · 请求结果码指标 seckill.request.total")
class SeckillControllerTest {

    private static final long USER_ID = 1001L;
    private static final long ACTIVITY_ID = 7L;

    @Mock
    private SeckillService seckillService;
    @Mock
    private SeckillTokenService seckillTokenService;

    private SimpleMeterRegistry registry;
    private SeckillController controller;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        controller = new SeckillController(seckillService, seckillTokenService, registry);
        UserContext.setUserId(USER_ID);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private double counter(int code) {
        return registry.get("seckill.request.total")
                .tag("code", String.valueOf(code))
                .counter().count();
    }

    @Test
    @DisplayName("buy 成功 → seckill.request.total{code=0} +1")
    void buySuccessCountsZeroCode() {
        when(seckillService.createOrder(eq(ACTIVITY_ID), eq(USER_ID), eq("path-1"), eq("token-1")))
                .thenReturn(new SeckillResponse("SK1", OrderStatus.CREATING, OffsetDateTime.now()));

        Result<SeckillResponse> result = controller.buy(ACTIVITY_ID, "path-1", "token-1");

        assertThat(result.getCode()).isZero();
        assertThat(counter(ErrorCode.SUCCESS.getCode())).isEqualTo(1.0);
    }

    @Test
    @DisplayName("凭证缺失 → 计数 code=1002 且异常上抛")
    void missingTokenCounts1002() {
        assertThatThrownBy(() -> controller.buy(ACTIVITY_ID, "path-1", "   "))
                .isInstanceOf(BizException.class);

        assertThat(counter(ErrorCode.MISSING_PARAMETER.getCode())).isEqualTo(1.0);
    }

    @Test
    @DisplayName("业务异常（库存不足）→ 按自身错误码计数")
    void bizExceptionCountsItsOwnCode() {
        when(seckillService.createOrder(anyLong(), anyLong(), anyString(), anyString()))
                .thenThrow(new BizException(ErrorCode.INSUFFICIENT_STOCK));

        assertThatThrownBy(() -> controller.buy(ACTIVITY_ID, "path-1", "token-1"))
                .isInstanceOf(BizException.class);

        assertThat(counter(ErrorCode.INSUFFICIENT_STOCK.getCode())).isEqualTo(1.0);
    }

    @Test
    @DisplayName("未分类运行时异常 → 计数 code=5001")
    void runtimeExceptionCountsSystemError() {
        when(seckillService.createOrder(anyLong(), anyLong(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> controller.buy(ACTIVITY_ID, "path-1", "token-1"))
                .isInstanceOf(IllegalStateException.class);

        assertThat(counter(ErrorCode.SYSTEM_ERROR.getCode())).isEqualTo(1.0);
    }
}