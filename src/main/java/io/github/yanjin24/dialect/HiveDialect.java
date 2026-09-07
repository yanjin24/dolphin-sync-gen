package io.github.yanjin24.dialect;

import cn.hutool.core.util.StrUtil;

import java.sql.Types;
import java.util.List;

/**
 * Hive 方言工具类。
 *
 * <p>与 {@link DialectHandler} 体系不同，Hive 建表不走"保留源类型名 + 正则重映射"的对称模型：
 * 为避免复杂的字段类型映射，所有源类型一律按 {@link java.sql.Types} 整型类别塌缩成三种 Hive 类型
 * （{@code bigint} / {@code double} / {@code string}），且不含主键、唯一键、NOT NULL、DEFAULT、自增等约束。
 * 因此这里以独立的静态工具方法提供"类型映射"与"整段 DDL 拼装"，不实现 {@link DialectHandler} 接口，
 * 也不影响现有 MySQL/PostgreSQL 路径。
 */
public class HiveDialect {

    private HiveDialect() {
    }

    // ──────────────────────── 类型映射 ────────────────────────

    /**
     * 将源库列的 {@link java.sql.Types} 代码映射为受限的三种 Hive 类型之一。
     *
     * <p>映射规则（3 类型约束下的取舍）：
     * <ul>
     *   <li>整数族（含 BOOLEAN/BIT，按 0/1 处理） → {@code bigint}</li>
     *   <li>浮点/定点族（含 DECIMAL/NUMERIC，金额类会损失精度） → {@code double}</li>
     *   <li>其余一切（字符、日期时间、二进制、PG 的 json/uuid/数组等 OTHER） → {@code string}</li>
     * </ul>
     *
     * @param sqlType {@code colRs.getInt("DATA_TYPE")}，即 java.sql.Types 代码
     * @return "bigint" / "double" / "string" 之一
     */
    public static String toHiveType(int sqlType) {
        switch (sqlType) {
            // 整数族（布尔按 0/1 归入 bigint）
            case Types.BIT:
            case Types.BOOLEAN:
            case Types.TINYINT:
            case Types.SMALLINT:
            case Types.INTEGER:
            case Types.BIGINT:
                return "bigint";
            // 浮点 / 定点族（DECIMAL/NUMERIC 在 3 类型约束下牺牲精度）
            case Types.REAL:
            case Types.FLOAT:
            case Types.DOUBLE:
            case Types.NUMERIC:
            case Types.DECIMAL:
                return "double";
            // 其余一切（字符、日期时间、二进制、json/uuid/数组等）统一为 string
            default:
                return "string";
        }
    }

    // ──────────────────────── DDL 拼装 ────────────────────────

    /**
     * 拼装一条 Hive {@code CREATE TABLE} 语句。
     *
     * <p>形如：
     * <pre>
     * CREATE TABLE IF NOT EXISTS `db`.`prefix_table_suffix` (
     *   `id` bigint COMMENT '主键',
     *   `name` string
     * )
     * COMMENT '表注释'
     * STORED AS ORC
     * </pre>
     *
     * @param database     Hive 库名（来自 output JDBC URL 路径段），为空则不加库前缀
     * @param tableName    完整表名（已含 prefix/suffix）
     * @param columnDefs   已拼好的列定义列表（每项形如 {@code `col` type COMMENT '...'}）
     * @param tableComment 表注释，可能为空
     * @param storageFormat 存储格式（orc / parquet / textfile），为空则默认 ORC
     * @return 完整可执行的 Hive DDL（不含结尾分号）
     */
    public static String buildCreateTableDDL(String database, String tableName, List<String> columnDefs,
                                             String tableComment, String storageFormat) {
        StringBuilder ddl = new StringBuilder("CREATE TABLE IF NOT EXISTS ");
        if (StrUtil.isNotBlank(database)) {
            ddl.append(quote(database)).append(".");
        }
        ddl.append(quote(tableName)).append(" (\n");
        ddl.append(String.join(",\n", columnDefs));
        ddl.append("\n)");
        if (StrUtil.isNotBlank(tableComment)) {
            ddl.append("\nCOMMENT '").append(escape(tableComment)).append("'");
        }
        // 默认 ORC：DataX hdfswriter 仅支持 orc/textfile，默认 ORC 可让"建表→同步"开箱即用
        String format = StrUtil.isBlank(storageFormat) ? "ORC" : storageFormat.trim().toUpperCase();
        ddl.append("\nSTORED AS ").append(format);
        return ddl.toString();
    }

    /**
     * 拼装单个列定义：{@code `col` type COMMENT 'remarks'}。
     *
     * @param columnName 列名
     * @param hiveType   Hive 类型（{@link #toHiveType}）
     * @param remarks    列注释，可能为空
     */
    public static String buildColumnDef(String columnName, String hiveType, String remarks) {
        StringBuilder col = new StringBuilder("  ");
        col.append(quote(columnName)).append(" ").append(hiveType);
        if (StrUtil.isNotBlank(remarks)) {
            col.append(" COMMENT '").append(escape(remarks)).append("'");
        }
        return col.toString();
    }

    // ──────────────────────── 工具 ────────────────────────

    /** 用反引号包裹标识符，规避 Hive 保留字（date、timestamp、location 等）。 */
    private static String quote(String identifier) {
        return "`" + identifier + "`";
    }

    /**
     * 转义 Hive 字符串字面量内容。
     *
     * <p>Hive 用反斜杠转义（与 MySQL/PG 的 {@code ''} 双写不同），需先转反斜杠再转单引号。
     */
    private static String escape(String literal) {
        return literal.replace("\\", "\\\\").replace("'", "\\'");
    }
}
