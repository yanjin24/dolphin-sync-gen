package io.github.yanjin24.dialect;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;

/**
 * 数据库方言处理器接口（DataX Reader/Writer 插件风格）。
 *
 * <p>每种数据库实现一个该接口，分别封装：
 * <ul>
 *   <li><b>Reader 侧</b>：从源库元数据读取列定义时，如何处理该数据库特有的类型精度、默认值格式等细节</li>
 *   <li><b>Writer 侧</b>：向目标库写 DDL 时，如何将来自任意源库的通用类型名映射成本库类型、如何写注释等</li>
 * </ul>
 *
 * <p>扩展新数据源只需：
 * <ol>
 *   <li>新建 {@code dialect/XxxDialect.java} 实现本接口</li>
 *   <li>在 {@link DialectFactory#getDialect(String)} 中注册一行映射</li>
 * </ol>
 */
public interface DialectHandler {

    // ──────────────────────── Reader 侧 ────────────────────────

    /**
     * 根据列的元数据，追加类型精度/长度到 columnDef。
     *
     * <p>实现示例（PostgreSQL 时间类型）：{@code columnDef.append("(").append(decimalDigits).append(")")}
     *
     * @param columnDef    当前列定义的 StringBuilder（已含列名和类型名）
     * @param typeName     原始 JDBC 类型名（已转小写）
     * @param columnSize   {@link ResultSet#getInt(String)} "COLUMN_SIZE"
     * @param decimalDigits {@link ResultSet#getInt(String)} "DECIMAL_DIGITS"
     */
    void appendTypePrecision(StringBuilder columnDef, String typeName, int columnSize, int decimalDigits);

    /**
     * 追加默认值子句到 columnDef。
     *
     * <p>不同数据库对 DEFAULT 的格式差异较大（PG 带 {@code ::type} 类型转换、
     * MySQL 字符型需加单引号等），由各方言自行处理。
     * 若无默认值（{@code defaultValue == null}）则不追加任何内容。
     *
     * @param columnDef    当前列定义的 StringBuilder
     * @param typeName     原始 JDBC 类型名（已转小写）
     * @param defaultValue {@link ResultSet#getString(String)} "COLUMN_DEF"，可能为 null
     * @param decimalDigits 时间精度，部分 PG 默认值需要附加
     */
    void appendDefaultValue(StringBuilder columnDef, String typeName,
                            String defaultValue, int decimalDigits);

    /**
     * 追加内联注释子句到 columnDef。
     *
     * <p>MySQL 支持 {@code COMMENT 'xxx'} 内联写法；PostgreSQL 不支持，
     * 注释需要通过 {@link #writeTableComments} 单独执行，此方法对 PG 应为空实现。
     *
     * @param columnDef 当前列定义的 StringBuilder
     * @param remarks   列注释，可能为空
     */
    void appendInlineComment(StringBuilder columnDef, String remarks);

    /**
     * 追加表级内联注释（CREATE TABLE 结尾的 {@code COMMENT 'xxx'}）。
     *
     * <p>MySQL 在建表语句末尾支持表注释；PostgreSQL 不支持，此方法应为空实现。
     *
     * @param ddl          完整 DDL 的 StringBuilder（在末尾 {@code );} 之前追加）
     * @param tableComment 表注释，可能为空
     */
    void appendTableComment(StringBuilder ddl, String tableComment);

    // ──────────────────────── Writer 侧 ────────────────────────

    /**
     * 将来自 <b>任意源库</b> 的通用 DDL 字符串转换为本方言的 DDL。
     *
     * <p>主要职责：
     * <ul>
     *   <li>数据类型名称映射（如 {@code TINYINT} → {@code int2}）</li>
     *   <li>schema 前缀的添加或替换</li>
     *   <li>去掉本方言不支持的语法（如 PG 不支持内联 COMMENT）</li>
     * </ul>
     *
     * @param ddl          来自源库的 DDL 字符串（由 {@link DialectHandler#appendTableComment} 等方法生成）
     * @param outputSchema 目标库的 schema 名称
     * @return 转换后的 DDL 字符串，可直接执行
     */
    String transformDDL(String ddl, String outputSchema);

    /**
     * 在目标库执行建表后，额外写入注释 SQL（仅 PostgreSQL 等需要单独 COMMENT ON 的数据库才有实质内容）。
     *
     * <p>MySQL 因为注释已内联在 DDL 中，此方法应为空实现。
     *
     * @param stmt        已打开的 {@link Statement}
     * @param outputSchema 目标库 schema
     * @param tableName   目标表名（已含 prefix和suffix）
     * @param comments    有序 Map：第一个 entry 为表注释（key=表名），后续为字段注释
     * @throws SQLException 执行 SQL 失败时抛出
     */
    void writeTableComments(Statement stmt, String outputSchema,
                            String tableName, LinkedHashMap<String, String> comments) throws SQLException;
}
