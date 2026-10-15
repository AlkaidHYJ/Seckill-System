package com.example.flashsale.exception;

import com.example.flashsale.common.ErrorCode;
import com.example.flashsale.common.Result;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.MDC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.validation.BindException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> handleBizException(BizException exception) {
        ErrorCode errorCode = exception.getErrorCode();
        return ResponseEntity.status(httpStatus(errorCode))
            .body(Result.failure(errorCode, exception.getMessage(), traceId()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class,
            ConstraintViolationException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleValidationException(Exception exception) {
        return Result.failure(ErrorCode.INVALID_PARAMETER, traceId());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleMessageNotReadableException(HttpMessageNotReadableException exception) {
        return Result.failure(ErrorCode.INVALID_REQUEST_BODY, traceId());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleTypeMismatchException(MethodArgumentTypeMismatchException exception) {
        return Result.failure(ErrorCode.INVALID_PARAMETER, traceId());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleException(Exception exception) {
        log.error("Unhandled request exception, traceId={}", traceId(), exception);
        return Result.failure(ErrorCode.SYSTEM_ERROR, traceId());
    }

    private String traceId() {
        return MDC.get("traceId");
    }

    private HttpStatus httpStatus(ErrorCode errorCode) {
        return switch (errorCode) {
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case ACTIVITY_NOT_FOUND, ORDER_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case TOO_MANY_REQUESTS -> HttpStatus.TOO_MANY_REQUESTS;
            case REDIS_UNAVAILABLE, MQ_PUBLISH_FAILED, DEPENDENCY_TIMEOUT ->
                    HttpStatus.SERVICE_UNAVAILABLE;
            case SYSTEM_ERROR, DATABASE_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
            default -> HttpStatus.BAD_REQUEST;
        };
    }
}