package com.example.flashsale.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 可观测（输出 2 慢 SQL）单元测试：直接用 Logback ListAppender 捕获 SlowSqlInterceptor 的 WARN，
 * 验证「仅 SELECT/UPDATE 生效、超阈值才 WARN、WARN 含 id/sql/params/elapsedMs/traceId、
 * 快速路径不构建 BoundSql」，覆盖阈值化与性能约束。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("可观测 · 慢 SQL 拦截器（仅 SELECT/UPDATE，超阈值才 WARN）")
class SlowSqlInterceptorTest {

    private static final String TRACE_ID = "trace-slow-1";

    @Mock
    private MappedStatement mappedStatement;
    @Mock
    private BoundSql boundSql;
    @Mock
    private Executor executor;

    private Logger interceptorLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        interceptorLogger = (Logger) LoggerFactory.getLogger(SlowSqlInterceptor.class);
        appender = new ListAppender<>();
        appender.start();
        interceptorLogger.addAppender(appender);
        MDC.put("traceId", TRACE_ID);
    }

    @AfterEach
    void tearDown() {
        interceptorLogger.detachAppender(appender);
        MDC.clear();
    }

    private Invocation queryInvocation() throws Exception {
        Method method = Executor.class.getMethod("query",
                MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class);
        return new Invocation(executor, method,
                new Object[]{mappedStatement, "param", new RowBounds(), null});
    }

    private Invocation updateInvocation() throws Exception {
        Method method = Executor.class.getMethod("update", MappedStatement.class, Object.class);
        return new Invocation(executor, method, new Object[]{mappedStatement, "param"});
    }

    private String slowWarnMessage() {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN
                        && e.getFormattedMessage().contains("Slow SQL detected"))
                .map(ILoggingEvent::getFormattedMessage)
                .findFirst()
                .orElse(null);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("SELECT 超阈值 → 一条 WARN，含 id/type/sql/params/elapsedMs/traceId")
    void slowSelectWarns() throws Throwable {
        when(mappedStatement.getSqlCommandType()).thenReturn(SqlCommandType.SELECT);
        when(mappedStatement.getId()).thenReturn("com.example.OrderMapper.list");
        when(mappedStatement.getBoundSql(any())).thenReturn(boundSql);
        when(boundSql.getSql()).thenReturn("SELECT * FROM t_order WHERE user_id = ?");
        when(executor.query(any(), any(), any(), any())).thenAnswer(inv -> {
            sleep(30);
            return null;
        });

        new SlowSqlInterceptor(5).intercept(queryInvocation());

        String warn = slowWarnMessage();
        assertThat(warn).isNotNull();
        assertThat(warn)
                .contains("id=com.example.OrderMapper.list")
                .contains("type=SELECT")
                .contains("SELECT * FROM t_order WHERE user_id = ?")
                .contains("elapsedMs=")
                .contains("traceId=" + TRACE_ID);
        verify(mappedStatement).getBoundSql(any());
    }

    @Test
    @DisplayName("SELECT 未超阈值 → 无 WARN，且从不构建 BoundSql（性能约束）")
    void fastSelectDoesNotWarn() throws Throwable {
        when(mappedStatement.getSqlCommandType()).thenReturn(SqlCommandType.SELECT);
        when(executor.query(any(), any(), any(), any())).thenReturn(null);

        new SlowSqlInterceptor(10_000).intercept(queryInvocation());

        assertThat(slowWarnMessage()).isNull();
        verify(mappedStatement, never()).getBoundSql(any());
    }

    @Test
    @DisplayName("UPDATE 超阈值 → WARN（type=UPDATE）")
    void slowUpdateWarns() throws Throwable {
        when(mappedStatement.getSqlCommandType()).thenReturn(SqlCommandType.UPDATE);
        when(mappedStatement.getId()).thenReturn("com.example.OrderMapper.update");
        when(mappedStatement.getBoundSql(any())).thenReturn(boundSql);
        when(boundSql.getSql()).thenReturn("UPDATE t_order SET status = ?");
        when(executor.update(any(), any())).thenAnswer(inv -> {
            sleep(30);
            return 1;
        });

        new SlowSqlInterceptor(5).intercept(updateInvocation());

        assertThat(slowWarnMessage()).isNotNull().contains("type=UPDATE");
    }

    @Test
    @DisplayName("INSERT 即使超阈值也不 WARN、不采样（不计入统计）")
    void insertIsSkipped() throws Throwable {
        when(mappedStatement.getSqlCommandType()).thenReturn(SqlCommandType.INSERT);
        when(executor.update(any(), any())).thenAnswer(inv -> {
            sleep(30);
            return 1;
        });

        new SlowSqlInterceptor(5).intercept(updateInvocation());

        assertThat(slowWarnMessage()).isNull();
        verify(mappedStatement, never()).getBoundSql(any());
    }
}