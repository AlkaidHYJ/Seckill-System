package com.example.flashsale.config;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Properties;

/**
 * 慢 SQL 日志拦截器（MyBatis Plugin）：仅对 SELECT（Executor#query）与 UPDATE（Executor#update 中的
 * UPDATE 语句）计时，执行耗时超阈值输出一条 WARN（含语句 ID、SQL、参数、耗时、traceId）。
 * 性能约束：快速路径只做 System.nanoTime() 两次采样；仅当超阈值时才构建 SQL/参数，避免常态开销。
 * 由 Spring Boot 自动装配将本 {@link Interceptor} bean 注入 SqlSessionFactory，无需额外配置类。
 */
@Intercepts({
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
        @Signature(type = Executor.class, method = "update",
                args = {MappedStatement.class, Object.class})
})
@Component
public class SlowSqlInterceptor implements Interceptor {

    private static final Logger log = LoggerFactory.getLogger(SlowSqlInterceptor.class);
    private static final String TRACE_ID_KEY = "traceId";
    private static final long NANOS_PER_MILLI = 1_000_000L;

    private final long thresholdMs;

    public SlowSqlInterceptor(@Value("${seckill.slow-sql.threshold-ms:500}") long thresholdMs) {
        this.thresholdMs = thresholdMs;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        MappedStatement mappedStatement = (MappedStatement) invocation.getArgs()[0];
        SqlCommandType commandType = mappedStatement.getSqlCommandType();
        if (commandType != SqlCommandType.SELECT && commandType != SqlCommandType.UPDATE) {
            return invocation.proceed();
        }

        long start = System.nanoTime();
        try {
            return invocation.proceed();
        } finally {
            long elapsedMs = (System.nanoTime() - start) / NANOS_PER_MILLI;
            if (elapsedMs > thresholdMs) {
                BoundSql boundSql = mappedStatement.getBoundSql(invocation.getArgs()[1]);
                log.warn("Slow SQL detected, id={}, type={}, elapsedMs={}, sql={}, params={}, traceId={}",
                        mappedStatement.getId(),
                        commandType,
                        elapsedMs,
                        boundSql.getSql(),
                        boundSql.getParameterObject(),
                        MDC.get(TRACE_ID_KEY));
            }
        }
    }

    @Override
    public Object plugin(Object target) {
        return Plugin.wrap(target, this);
    }

    @Override
    public void setProperties(Properties properties) {
        // 阈值通过构造注入，暂不支持 XML properties 覆盖
    }
}