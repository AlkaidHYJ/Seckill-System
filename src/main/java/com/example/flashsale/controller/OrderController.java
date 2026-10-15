package com.example.flashsale.controller;

import com.example.flashsale.common.Result;
import com.example.flashsale.common.UserContext;
import com.example.flashsale.service.OrderService;
import com.example.flashsale.service.PayService;
import com.example.flashsale.vo.OrderVO;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.MDC;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;
    private final PayService payService;

    public OrderController(OrderService orderService, PayService payService) {
        this.orderService = orderService;
        this.payService = payService;
    }

    @GetMapping("/{orderNo}")
    public Result<OrderVO> getOrder(
            @PathVariable @NotBlank @Size(max = 32) String orderNo) {
        return Result.success(orderService.findOwned(orderNo, UserContext.getRequiredUserId()), traceId());
    }

    @PostMapping("/{orderNo}/pay")
    public Result<OrderVO> pay(
            @PathVariable @NotBlank @Size(max = 32) String orderNo) {
        return Result.success(payService.pay(orderNo, UserContext.getRequiredUserId()), traceId());
    }

    private String traceId() {
        return MDC.get("traceId");
    }
}