package io.github.yanjin24.dialect;

import cn.hutool.core.util.StrUtil;

/**
 * MySQL 方言实现。
 *
 * <p><b>Reader 侧特点：</b>
 * <ul>
 *   <li>时间类型精度需通过 columnSize 推算（JDBC 返回的是字符串宽度而非精度值）</li>
 *   <li>字符型 DEFAULT 需要加单引号包裹</li>
 *   <li>列注释支持内联 {@code COMMENT 'xxx'} 语法</li>
 *   <li>表注释支持 {@code ) COMMENT 'xxx';} 语法</li>
 * </ul>
 *
 * <p><b>Writer 侧特点（接收任意源库 DDL 并转换）：</b>
 * <ul>
 *   <li>去掉 schema 前缀（MySQL 无 schema 概念）</li>
 *   <li>将 PostgreSQL 类型名反向映射为 MySQL 类型名</li>
 * </ul>
 */
public class MySQLDialect extends AbstractDialect {

    // ──────────────────────── Reader 侧 ────────────────────────

    @Override
    public void appendTypePrecision(StringBuilder columnDef, String typeName,
                                    int columnSize, int decimalDigits) {
        String lowerType = typeName.toLowerCase();
        // numeric/decimal 和 char/varchar 由公共方法处理
        appendCommonPrecision(columnDef, lowerType, columnSize, decimalDigits);

        // 时间类型：MySQL JDBC 返回的是字符串宽度，需反推精度
        if (isTimeType(lowerType)) {
            int precision = calcMySQLTimePrecision(lowerType, columnSize);
            columnDef.append("(").append(precision).append(")");
        }
    }

    @Override
    public void appendDefaultValue(StringBuilder columnDef, String typeName,
                                   String defaultValue, int decimalDigits) {
        if (defaultValue == null) return;
        if (typeRequiresLength(typeName.toLowerCase())) {
            // 字符型默认值加单引号
            columnDef.append(" DEFAULT '").append(defaultValue).append("'");
        } else {
            columnDef.append(" DEFAULT ").append(defaultValue);
        }
    }

    @Override
    public void appendInlineComment(StringBuilder columnDef, String remarks) {
        if (StrUtil.isNotBlank(remarks)) {
            columnDef.append(" COMMENT '").append(remarks).append("'");
        }
    }

    @Override
    public void appendTableComment(StringBuilder ddl, String tableComment) {
        if (StrUtil.isNotBlank(tableComment)) {
            ddl.append("\n) COMMENT '").append(tableComment).append("';");
        } else {
            ddl.append("\n);");
        }
    }

    // ──────────────────────── Writer 侧 ────────────────────────

    /**
     * 将任意源库 DDL 转换为 MySQL 可执行的 DDL。
     *
     * <p>当前已处理 PostgreSQL → MySQL 的类型映射；若源库已是 MySQL，原样返回。
     */
    @Override
    public String transformDDL(String ddl, String outputSchema) {
        // 去掉 schema.前缀（MySQL 不使用 schema 限定）
        return ddl
                .replaceAll("(?<=EXISTS\\s)[^.]+\\.", "")
                // PG → MySQL 类型映射
                .replaceAll("\\bbpchar\\b",              "char")
                .replaceAll("\\bvarchar\\b(?!\\()",       "text")    // 无长度 varchar → text
                .replaceAll("(?i)\\btimestamptz\\b",      "timestamp")
                .replaceAll("(?i)\\btimestamp\\b(?!tz)",  "datetime")
                .replaceAll("\\bfloat4\\b",               "float")
                .replaceAll("\\bfloat8\\b",               "double")
                .replaceAll("\\bbytea\\b",                "longblob")
                .replaceAll("\\bjsonb\\b",                "json");
    }

    // ──────────────────────── 私有工具 ────────────────────────

    /**
     * MySQL JDBC 驱动对时间类型返回的 COLUMN_SIZE 是字符串显示宽度，
     * 需从显示宽度中反推精度（小数秒位数）。
     *
     * <pre>
     * DATETIME(0) / TIMESTAMP(0)：显示宽度 = 19（"YYYY-MM-DD HH:MM:SS"）
     * DATETIME(n) / TIMESTAMP(n)：显示宽度 = 19 + 1（小数点）+ n
     * TIME(0)：显示宽度 = 8（"HH:MM:SS"）
     * TIME(n)：显示宽度 = 8 + 1 + n
     * </pre>
     */
    private int calcMySQLTimePrecision(String lowerType, int columnSize) {
        int baseWidth;
        switch (lowerType) {
            case "timestamp":
            case "datetime":  baseWidth = 19; break;
            case "time":      baseWidth = 8;  break;
            default:          return 0;
        }
        if (columnSize > baseWidth) {
            return (columnSize - baseWidth) - 1;
        }
        return 0;
    }
}
