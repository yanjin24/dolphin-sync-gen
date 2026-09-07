package io.github.yanjin24.dialect;

import cn.hutool.core.util.StrUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PostgreSQL 方言实现。
 *
 * <p><b>Reader 侧特点：</b>
 * <ul>
 *   <li>时间类型精度直接使用 {@code decimalDigits}</li>
 *   <li>DEFAULT 值可能携带 {@code ::type} 类型转换后缀，需去掉</li>
 *   <li>不支持列内联 COMMENT；注释需通过 {@code COMMENT ON} 单独执行</li>
 * </ul>
 *
 * <p><b>Writer 侧特点（接收任意源库 DDL 并转换）：</b>
 * <ul>
 *   <li>在表名前插入 schema 前缀，或替换已有 schema 前缀</li>
 *   <li>将 MySQL 类型名映射为 PostgreSQL 类型名</li>
 *   <li>去掉内联 COMMENT 子句（PG 不支持该语法）</li>
 * </ul>
 */
public class PostgreSQLDialect extends AbstractDialect {

    private static final Logger log = LogManager.getLogger(PostgreSQLDialect.class);

    // ──────────────────────── Reader 侧 ────────────────────────

    @Override
    public void appendTypePrecision(StringBuilder columnDef, String typeName,
                                    int columnSize, int decimalDigits) {
        String lowerType = typeName.toLowerCase();
        // numeric/decimal 和 char/varchar 由公共方法处理
        appendCommonPrecision(columnDef, lowerType, columnSize, decimalDigits);

        // 时间类型：PG JDBC 的 decimalDigits 直接就是精度
        if (isTimeType(lowerType)) {
            columnDef.append("(").append(decimalDigits).append(")");
        }
    }

    @Override
    public void appendDefaultValue(StringBuilder columnDef, String typeName,
                                   String defaultValue, int decimalDigits) {
        if (defaultValue == null) return;
        // 去掉 PG 特有的 ::type 类型转换后缀（如 ''::character varying）
        int castIndex = defaultValue.indexOf("::");
        if (castIndex != -1) {
            defaultValue = defaultValue.substring(0, castIndex);
        }
        columnDef.append(" DEFAULT ").append(defaultValue);
        // CURRENT_TIMESTAMP 需要补充精度
        if ("CURRENT_TIMESTAMP".equals(defaultValue)) {
            columnDef.append("(").append(decimalDigits).append(")");
        }
    }

    // appendInlineComment：继承 AbstractDialect 的空实现，PG 不写内联注释
    // appendTableComment：继承 AbstractDialect 的空实现，PG 不写表尾注释（直接 );）

    @Override
    public void appendTableComment(StringBuilder ddl, String tableComment) {
        // PostgreSQL 建表语句统一以 ); 结尾，注释通过 COMMENT ON 单独执行
        ddl.append("\n);");
    }

    // ──────────────────────── Writer 侧 ────────────────────────

    /**
     * 将任意源库 DDL 转换为 PostgreSQL 可执行的 DDL。
     *
     * <p>当前已处理 MySQL → PostgreSQL 的类型映射；若源库已是 PostgreSQL，
     * 则仅替换 schema 名并去掉内联 COMMENT 子句。
     */
    @Override
    public String transformDDL(String ddl, String outputSchema) {
        // 若 DDL 中已有 schema（如 PG→PG 场景），替换为目标 schema；
        // 若没有 schema（如 MySQL→PG 场景），在表名前插入 schema 前缀。
        String result;
        if (ddl.matches("(?s).*EXISTS\\s+\\S+\\..*")) {
            // 已有 schema.tableName 结构：替换 schema 部分
            result = ddl.replaceAll("(?<=EXISTS\\s)[^.]+(?=\\.)", outputSchema);
        } else {
            // 无 schema 前缀：在表名前插入 "schema."
            result = ddl.replaceAll("(?<=EXISTS\\s)(?=\\S+)", outputSchema + ".");
        }

        return result
                // MySQL → PG 类型映射
                .replaceAll("\\bTINYINT\\b",   "int2")
                .replaceAll("\\bBIT\\(1\\)",    "bool")    // TINYINT(1) 经 JDBC 转成 BIT(1)
                .replaceAll("\\bFLOAT\\b",      "float4")
                .replaceAll("\\bDOUBLE\\b",     "float8")
                .replaceAll("\\bDATETIME\\b",   "timestamp")
                .replaceAll("\\bTIMESTAMP\\b",  "timestamptz")
                .replaceAll("(?i)\\bTINYBLOB\\b",   "bytea")
                .replaceAll("(?i)\\bMEDIUMBLOB\\b",  "bytea")
                .replaceAll("(?i)\\bLONGBLOB\\b",    "bytea")
                .replaceAll("\\bBLOB\\b",       "bytea")
                // 去掉 MySQL 内联 COMMENT 子句（PG 不支持该语法）
                .replaceAll(" COMMENT '(?:''|[^'])*'", "");
    }

    /**
     * 在目标库执行 {@code COMMENT ON TABLE} 和 {@code COMMENT ON COLUMN} 语句。
     *
     * <p>{@code comments} 中第一个 entry 为表注释（key = 表名），其余为字段注释。
     */
    @Override
    public void writeTableComments(Statement stmt, String outputSchema,
                                   String tableName, LinkedHashMap<String, String> comments)
            throws SQLException {
        if (comments.isEmpty()) return;

        // 第一条为表注释
        Map.Entry<String, String> first = comments.entrySet().iterator().next();
        String tableComment = first.getValue();
        if (StrUtil.isNotBlank(tableComment)) {
            String sql = String.format("COMMENT ON TABLE %s.%s IS '%s';",
                    outputSchema, tableName, tableComment);
            log.info(sql);
            try {
                stmt.executeUpdate(sql);
            } catch (SQLException e) {
                log.error("添加表 {} 注释出错: {}", tableName, e.getMessage());
            }
        }

        // 后续条目为字段注释（跳过第一条）
        boolean isFirst = true;
        for (Map.Entry<String, String> entry : comments.entrySet()) {
            if (isFirst) { isFirst = false; continue; }
            String sql = String.format("COMMENT ON COLUMN %s.%s.%s IS '%s';",
                    outputSchema, tableName, entry.getKey(), entry.getValue());
            log.info(sql);
            try {
                stmt.executeUpdate(sql);
            } catch (SQLException e) {
                log.error("添加字段 {} 注释出错: {}", entry.getKey(), e.getMessage());
            }
        }
    }
}
