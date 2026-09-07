package io.github.yanjin24;

import cn.hutool.core.util.StrUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import io.github.yanjin24.dialect.DialectFactory;
import io.github.yanjin24.dialect.DialectHandler;
import io.github.yanjin24.entity.TableMetaData;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

/**
 * 跨库建表核心类。
 *
 * <p>架构说明（DataX Reader/Writer 插件风格）：
 * <ul>
 *   <li>本类只负责流程编排，不包含任何数据库方言细节</li>
 *   <li>方言细节全部委托给 {@link DialectHandler} 的具体实现（Reader 侧 + Writer 侧）</li>
 *   <li>新增数据源只需：① 实现 {@link DialectHandler}；② 在 {@link DialectFactory} 注册一行</li>
 * </ul>
 *
 * <pre>
 * 流程：
 *   1. 读配置，通过 DialectFactory 获取 inputDialect（Reader）和 outputDialect（Writer）
 *   2. generateTableDDL()：通过 inputDialect 读取源库元数据，生成中间 DDL
 *   3. outputDialect.transformDDL()：将中间 DDL 转换为目标库可执行的 DDL
 *   4. 执行 DDL；执行后调用 outputDialect.writeTableComments() 写入注释
 * </pre>
 */
public class CreateTable {

    private static final Logger log = LogManager.getLogger(CreateTable.class);

    // ===================== 对外入口 =====================

    public static void execute(SyncConfig config, boolean isRequiresKey) {
        String inputJdbcUrl   = config.getInputJdbcUrl();
        String inputUserName  = config.getInputUserName();
        String inputPassword  = config.getInputPassword();
        String outputJdbcUrl  = config.getOutputJdbcUrl();
        String outputUserName = config.getOutputUserName();
        String outputPassword = config.getOutputPassword();
        String prefix         = config.getPrefix();
        String suffix         = config.getSuffix();
        String[] split        = config.tableArray();

        // 从 JDBC URL 中提取数据库类型，由工厂获取对应方言处理器
        String inputType  = extractDatabaseType(inputJdbcUrl);
        String outputType = extractDatabaseType(outputJdbcUrl);
        DialectHandler inputDialect  = DialectFactory.getDialect(inputType);
        DialectHandler outputDialect = DialectFactory.getDialect(outputType);

        try (Connection conn = DriverManager.getConnection(outputJdbcUrl, outputUserName, outputPassword);
             Statement stmt = conn.createStatement()) {
            int i = 1;
            String outputSchema = conn.getSchema();
            for (String tableName : split) {
                try {
                    TableMetaData meta = generateTableDDL(
                            inputJdbcUrl, inputUserName, inputPassword,
                            inputDialect, tableName, prefix, suffix ,isRequiresKey);
                    if (meta == null) continue;

                    // Writer 侧：类型映射 + schema 替换 + 语法转换
                    String finalDDL = outputDialect.transformDDL(meta.getDdl().toString(), outputSchema);
                    if (!finalDDL.contains("不存在")) {
                        log.info("{}. {}", i++, finalDDL);
                        stmt.executeUpdate(finalDDL);
                        log.info("{}建表语句已执行", prefix + tableName + suffix);
                        // Writer 侧：写额外注释 SQL（如 PostgreSQL 的 COMMENT ON；MySQL 为空实现）
                        outputDialect.writeTableComments(stmt, outputSchema,
                                prefix + tableName + suffix, meta.getComments());
                        SQLWarning warning = stmt.getWarnings();
                        while (warning != null) {
                            log.warn("注意: {}", warning.getMessage());
                            warning = warning.getNextWarning();
                        }
                        stmt.clearWarnings(); // mysql会保留所有警告；postgresql仅保留最近一次
                    } else {
                        log.warn(finalDDL);
                    }
                } catch (SQLException e) {
                    log.error("创建表 {} 时出错: {}", prefix + tableName + suffix, e.getMessage());
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // ===================== 从源库读取表结构，生成中间 DDL =====================

    /**
     * 连接源库，通过 {@code inputDialect}（Reader 侧）读取元数据并生成 DDL。
     *
     * <p>生成的 DDL 是"方言中立"的中间格式：
     * 类型名保留源库原始名称，注释等源库特有语法由 inputDialect 内联追加。
     * 写入目标库之前，由 outputDialect.transformDDL() 完成类型映射和语法转换。
     */
    private static TableMetaData generateTableDDL(String jdbcUrl, String userName, String password,
                                                   DialectHandler inputDialect,
                                                   String tableName, String prefix,String suffix,
                                                   boolean isRequiresKey) throws SQLException {
        // postgresql 中 getTables/getColumns 若 tableName 为空会返回所有表
        if (StrUtil.isBlank(tableName)) return null;

        Properties props = new Properties();
        props.setProperty("user", userName);
        props.setProperty("password", password);
        props.setProperty("useInformationSchema", "true"); // mysql5.7 需改为 true 才能获取表注释

        try (Connection conn = DriverManager.getConnection(jdbcUrl, props)) {
            DatabaseMetaData metaData = conn.getMetaData();
            String catalog  = conn.getCatalog(); // mysql 的 catalog 即数据库名，需指定以避免查询到其他库
            String schema   = conn.getSchema();
            List<String> columns      = new ArrayList<>();
            LinkedHashMap<String, String> comments = new LinkedHashMap<>(); // postgresql 需单独收集字段注释
            List<String> serialColumns = new ArrayList<>(); // 记录自增列，用于后面排除多余 UNIQUE

            // 1. 检查表是否存在，读取表注释
            String tableComment;
            try (ResultSet tableRs = metaData.getTables(catalog, schema, tableName, new String[]{"TABLE"})) {
                if (tableRs.next()) {
                    String remarks = tableRs.getString("REMARKS"); // 没有表注释时 postgresql 为 null
                    tableComment = remarks != null ? remarks.replace("'", "''") : "";
                    comments.put(tableName, tableComment);
                } else {
                    StringBuilder msg = new StringBuilder("来源表").append(tableName).append("不存在");
                    return new TableMetaData(msg, comments, schema, tableName);
                }
            }

            // 2. 遍历所有列，委托 inputDialect 处理方言差异
            try (ResultSet colRs = metaData.getColumns(catalog, schema, tableName, null)) {
                while (colRs.next()) {
                    String columnName    = colRs.getString("COLUMN_NAME");
                    String typeName      = colRs.getString("TYPE_NAME");
                    String remarks       = colRs.getString("REMARKS");
                    int    columnSize    = colRs.getInt("COLUMN_SIZE");
                    int    decimalDigits = colRs.getInt("DECIMAL_DIGITS");
                    int    nullable      = colRs.getInt("NULLABLE");
                    String defaultValue  = colRs.getString("COLUMN_DEF");
                    String isAutoIncrement = colRs.getString("IS_AUTOINCREMENT");

                    // 收集注释（postgresql 无注释时为 null，mysql 无注释时为 ''）
                    if (StrUtil.isNotBlank(remarks)) {
                        remarks = remarks.replace("'", "''"); // 注释内容的单引号转义
                        comments.put(columnName, remarks);
                    }

                    StringBuilder columnDef = new StringBuilder();

                    // mysql 和 postgresql 的 serial 输出写法差异极大，但可统一用 serial 输入
                    if ("YES".equalsIgnoreCase(isAutoIncrement)) {
                        columnDef.append(columnName).append(" serial");
                        // Reader 侧内联注释（PG 为空实现，MySQL 会追加 COMMENT 'xxx'）
                        inputDialect.appendInlineComment(columnDef, remarks);
                        columns.add(columnDef.toString());
                        serialColumns.add(columnName);
                        continue;
                    }

                    // 列名 + 类型名
                    columnDef.append(columnName).append(" ").append(typeName);

                    // ① 类型精度（委托 Reader 侧方言）
                    inputDialect.appendTypePrecision(columnDef, typeName, columnSize, decimalDigits);

                    // ② NOT NULL
                    if (nullable == DatabaseMetaData.columnNoNulls) {
                        columnDef.append(" NOT NULL");
                    }

                    // ③ DEFAULT（委托 Reader 侧方言）
                    inputDialect.appendDefaultValue(columnDef, typeName, defaultValue, decimalDigits);

                    // ④ 内联注释（委托 Reader 侧方言；PG 为空实现，MySQL 追加 COMMENT 'xxx'）
                    inputDialect.appendInlineComment(columnDef, remarks);

                    columns.add(columnDef.toString());
                }
            }

            // 3. 读取主键
            Map<Short, String> primaryKeyMap = new TreeMap<>();
            Set<String> primaryKeyNames = new HashSet<>();
            Map<String, TreeMap<Short, String>> uniqueConstraints = new LinkedHashMap<>();//唯一键的字段组
            if (isRequiresKey) {
                try (ResultSet pkRs = metaData.getPrimaryKeys(catalog, schema, tableName)) {
                    while (pkRs.next()) {
                        String pkColumn = pkRs.getString("COLUMN_NAME");
                        short keySeq = pkRs.getShort("KEY_SEQ"); // 键字段序号
                        primaryKeyMap.put(keySeq, pkColumn);
                        String pkName = pkRs.getString("PK_NAME");
                        if (pkName != null) primaryKeyNames.add(pkName);
                    }
                }
                // 读取唯一键
                try (ResultSet idxRs = metaData.getIndexInfo(catalog, schema, tableName, false, false)) {
                    while (idxRs.next()) {
                        String  indexName = idxRs.getString("INDEX_NAME");
                        boolean nonUnique = idxRs.getBoolean("NON_UNIQUE");
                        String  colName   = idxRs.getString("COLUMN_NAME");
                        short   seq       = idxRs.getShort("ORDINAL_POSITION");
                        short   type      = idxRs.getShort("TYPE"); // TYPE=0 即 tableIndexStatistic，需排除
                        // 排除主键索引和统计信息
                        if (primaryKeyNames.contains(indexName)
                                || type == DatabaseMetaData.tableIndexStatistic
                                || colName == null) continue;
                        if (!nonUnique) {
                            uniqueConstraints
                                    .computeIfAbsent(indexName, k -> new TreeMap<>())
                                    .put(seq, colName);
                        }
                    }
                }
                // mysql 使用 serial 会自动带 UNIQUE，排除 DDL 中重复的 UNIQUE 约束
                uniqueConstraints.entrySet().removeIf(entry -> {
                    TreeMap<Short, String> cols = entry.getValue();
                    return cols.size() == 1 && serialColumns.contains(cols.get((short) 1));
                });
            }

            // 4. 拼装 DDL
            StringBuilder ddl = new StringBuilder("CREATE TABLE IF NOT EXISTS ");
            if (schema != null) ddl.append(schema).append(".");
            ddl.append(prefix).append(tableName).append(suffix).append("(\n");
            ddl.append(String.join(",\n", columns));
            if (isRequiresKey) {
                if (!primaryKeyMap.isEmpty()) {
                    ddl.append(",\n\tPRIMARY KEY (");
                    ddl.append(String.join(", ", primaryKeyMap.values()));
                    ddl.append(")");
                }
                for (Map.Entry<String, TreeMap<Short, String>> entry : uniqueConstraints.entrySet()) {
                    ddl.append(",\n\t");
                    ddl.append("UNIQUE (").append(String.join(", ", entry.getValue().values())).append(")");
                }
            }

            // ⑤ 表级注释（委托 Reader 侧方言：MySQL 追加 ") COMMENT '...';"，PG 追加 ");"）
            inputDialect.appendTableComment(ddl, tableComment);

            return new TableMetaData(ddl, comments, schema, tableName);
        }
    }

    // ===================== 工具方法 =====================

    /** 从 JDBC URL 中提取数据库类型标识符（小写）。例如 jdbc:mysql://... → "mysql" */
    private static String extractDatabaseType(String jdbcUrl) {
        return jdbcUrl.replaceAll("jdbc:([^:]+):.*", "$1").toLowerCase();
    }
}
