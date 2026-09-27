package com.github.highcumontoa.batterysafetymonitorjava.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 统一错误归类：畸形 JSON / 不可读请求体归类为 MALFORMED_PAYLOAD，不静默吞掉。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Map<String, Object> handleUnreadable(HttpMessageNotReadableException ex) {
        log.warn("[入口] 请求体不可读 reason=MALFORMED_PAYLOAD detail={}", ex.getMessage());
        return Map.of("accepted", false,
                "reason", "MALFORMED_PAYLOAD",
                "message", "请求体不是合法 JSON 或字段类型错误: " + ex.getMostSpecificCause().getMessage());
    }
}
