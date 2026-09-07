package io.github.yanjin24.dialect;

import java.util.HashMap;
import java.util.Map;

/**
 * 方言工厂：根据 JDBC URL 中提取的数据库类型名称（如 "mysql"、"postgresql"）
 * 返回对应的 {@link DialectHandler} 实现。
 *
 * <p><b>扩展新数据源</b>：只需在 {@link #REGISTRY} 中新增一行注册，无需修改其他代码。
 */
public class DialectFactory {

    /**
     * 全局方言注册表。
     * key 为 JDBC URL 中提取的数据库类型名称（全小写），value 为对应的方言实现。
     */
    private static final Map<String, DialectHandler> REGISTRY = new HashMap<>();

    static {
        // ── 注册内置方言 ──────────────────────────────────────────────
        REGISTRY.put("mysql",      new MySQLDialect());
        REGISTRY.put("postgresql", new PostgreSQLDialect());
        // 扩展示例（取消注释并实现对应类即可）：
        // REGISTRY.put("oracle",     new OracleDialect());
        // REGISTRY.put("sqlserver",  new SQLServerDialect());
        // REGISTRY.put("clickhouse", new ClickHouseDialect());
    }

    /**
     * 根据数据库类型名称获取对应的方言处理器。
     *
     * @param databaseType JDBC URL 中提取的数据库类型（如 "mysql"），大小写不敏感
     * @return 对应的 {@link DialectHandler}
     * @throws IllegalArgumentException 若没有注册对应方言
     */
    public static DialectHandler getDialect(String databaseType) {
        DialectHandler handler = REGISTRY.get(databaseType.toLowerCase());
        if (handler == null) {
            throw new IllegalArgumentException("不支持的数据库类型: " + databaseType
                    + "，已注册的类型: " + REGISTRY.keySet());
        }
        return handler;
    }
}
