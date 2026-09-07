package io.github.yanjin24.dialect;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;

/**
 * 抽象方言基类，提供各数据库通用的默认实现，减少子类重复代码。
 *
 * <p>目前两个内置方言（MySQL / PostgreSQL）都需要处理 numeric/decimal 精度、
 * char/varchar 长度追加等逻辑，统一在此实现。
 */
public abstract class AbstractDialect implements DialectHandler {

    // ──────────────────────── 公共类型判断工具 ────────────────────────

    /** 是否为需要追加精度 {@code (size, scale)} 的数值类型 */
    protected boolean isNumericType(String lowerType) {
        return "numeric".equals(lowerType) || "decimal".equals(lowerType);
    }

    /** 是否为时间相关类型 */
    protected boolean isTimeType(String lowerType) {
        return lowerType.equals("timestamp") || lowerType.equals("time")
                || lowerType.equals("timestamptz") || lowerType.equals("timetz")
                || lowerType.equals("datetime");
    }

    /** 是否为需要追加长度 {@code (n)} 的字符类型 */
    protected boolean typeRequiresLength(String lowerType) {
        return lowerType.startsWith("varchar") || lowerType.startsWith("char")
                || lowerType.startsWith("bpchar") || lowerType.startsWith("varbit")
                || lowerType.startsWith("bit")    || lowerType.startsWith("varbinary")
                || lowerType.startsWith("binary");
    }

    // ──────────────────────── 公共精度追加逻辑 ────────────────────────

    /**
     * 处理 numeric/decimal 精度 和 char/varchar 长度，子类复用。
     * 时间精度、默认值格式因方言差异较大，由子类各自实现 {@link #appendTypePrecision}。
     */
    protected void appendCommonPrecision(StringBuilder columnDef, String lowerType,
                                         int columnSize, int decimalDigits) {
        if (isNumericType(lowerType)) {
            if (columnSize > 0) {
                columnDef.append("(").append(columnSize);
                if (decimalDigits > 0) columnDef.append(",").append(decimalDigits);
                columnDef.append(")");
            }
        } else if (typeRequiresLength(lowerType)) {
            // PostgreSQL 不带长度的 varchar 时 columnSize=2147483647，跳过
            if (columnSize > 0 && columnSize < 10485760) {
                columnDef.append("(").append(columnSize).append(")");
            }
        }
    }

    // ──────────────────────── 默认空实现 ────────────────────────

    /**
     * 默认不写内联注释（PostgreSQL 行为）。MySQL 方言重写此方法。
     */
    @Override
    public void appendInlineComment(StringBuilder columnDef, String remarks) {
        // 默认不追加，子类按需重写
    }

    /**
     * 默认不写表级内联注释（PostgreSQL 行为）。MySQL 方言重写此方法。
     */
    @Override
    public void appendTableComment(StringBuilder ddl, String tableComment) {
        // 默认不追加，子类按需重写
    }

    /**
     * 默认不额外执行注释 SQL（MySQL 行为）。PostgreSQL 方言重写此方法。
     */
    @Override
    public void writeTableComments(Statement stmt, String outputSchema,
                                   String tableName, LinkedHashMap<String, String> comments)
            throws SQLException {
        // 默认不执行，子类按需重写
    }
}
