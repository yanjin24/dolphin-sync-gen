package io.github.yanjin24;

import cn.hutool.core.util.StrUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import io.github.yanjin24.dialect.HiveDialect;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * 根据 MySQL/PostgreSQL 源表结构，生成对应的 Hive 建表 DDL 并打印到控制台。
 *
 * <p>与 {@link CreateTable} 平行的另一条建表路径，专用于"源永远是 RDBMS、目标是 Hive"的场景。
 * 为避免复杂的字段类型映射，Hive 列类型被限制为三种：{@code bigint} / {@code double} / {@code string}，
 * 由 {@link HiveDialect#toHiveType(int)} 按 {@link java.sql.Types} 整型类别归类。
 *
 * <pre>
 * 流程：
 *   1. 连源库（input*），用 DatabaseMetaData 读表存在性、列（名/类型/注释）、表注释
 *   2. 逐列调 HiveDialect.toHiveType 拼列定义，再由 HiveDialect 拼整段 Hive DDL
 *   3. 逐张表打印 DDL（不执行），由人工复制到 beeline/hive 客户端执行
 * </pre>
 *
 * <p>不连 Hive：省去 46MB 的 hive-jdbc standalone 依赖，也让建表时机由人工掌控。
 * Hive 建表不涉及主键/唯一键/NOT NULL/DEFAULT/自增；同步类参数（where/specified/deleteWhere 等）忽略。
 */
public class CreateHiveTable {

    private static final Logger log = LogManager.getLogger(CreateHiveTable.class);

    public static void execute(SyncConfig config) {
        // 从 output 的 Hive JDBC URL 解析库名，用于 DDL 的 库.表 前缀；URL 未填库名则为空
        String hiveDatabase = extractHiveDatabase(config.getOutputJdbcUrl());

        int i = 1;
        for (String tableName : config.tableArray()) {
            try {
                String ddl = generateHiveDDL(config.getInputJdbcUrl(), config.getInputUserName(),
                        config.getInputPassword(), tableName, hiveDatabase,
                        config.getPrefix(), config.getSuffix(), config.getHiveStorageFormat());
                if (ddl == null) continue;

                log.info("{}. {}\n{}\n", i++, config.getPrefix() + tableName + config.getSuffix(), ddl + ";");
            } catch (SQLException e) {
                log.error("生成 Hive 表 {} 的建表 DDL 出错: {}",
                        config.getPrefix() + tableName + config.getSuffix(), e.getMessage());
            }
        }
    }

    // ===================== 从源库读取表结构，生成 Hive DDL =====================

    /**
     * 连接源库读取元数据，拼出一条 Hive {@code CREATE TABLE} 语句。
     *
     * @return 完整 DDL；表名为空或源表不存在时返回 {@code null}（调用方跳过）
     */
    private static String generateHiveDDL(String jdbcUrl, String userName, String password,
                                          String tableName, String hiveDatabase,
                                          String prefix, String suffix, String storageFormat) throws SQLException {
        // postgresql 中 getTables/getColumns 若 tableName 为空会返回所有表
        if (StrUtil.isBlank(tableName)) return null;

        Properties props = new Properties();
        props.setProperty("user", userName);
        props.setProperty("password", password);
        props.setProperty("useInformationSchema", "true"); // mysql5.7 需改为 true 才能获取表注释

        try (Connection conn = DriverManager.getConnection(jdbcUrl, props)) {
            DatabaseMetaData metaData = conn.getMetaData();
            String catalog = conn.getCatalog(); // mysql 的 catalog 即数据库名，需指定以避免查询到其他库
            String schema  = conn.getSchema();

            // 1. 检查表是否存在，读取表注释
            String tableComment;
            try (ResultSet tableRs = metaData.getTables(catalog, schema, tableName, new String[]{"TABLE"})) {
                if (tableRs.next()) {
                    String remarks = tableRs.getString("REMARKS"); // 没有表注释时 postgresql 为 null
                    tableComment = remarks != null ? remarks : "";
                } else {
                    log.warn("来源表 {} 不存在，跳过", tableName);
                    return null;
                }
            }

            // 2. 遍历所有列，按 java.sql.Types 整型类别映射为三种 Hive 类型
            List<String> columnDefs = new ArrayList<>();
            try (ResultSet colRs = metaData.getColumns(catalog, schema, tableName, null)) {
                while (colRs.next()) {
                    String columnName = colRs.getString("COLUMN_NAME");
                    int    dataType   = colRs.getInt("DATA_TYPE"); // java.sql.Types 代码
                    String remarks    = colRs.getString("REMARKS");
                    String hiveType   = HiveDialect.toHiveType(dataType);
                    columnDefs.add(HiveDialect.buildColumnDef(columnName, hiveType, remarks));
                }
            }
            if (columnDefs.isEmpty()) {
                log.warn("来源表 {} 无列信息，跳过", tableName);
                return null;
            }

            // 3. 拼装 Hive DDL
            String outputTableName = prefix + tableName + suffix;
            return HiveDialect.buildCreateTableDDL(hiveDatabase, outputTableName, columnDefs,
                    tableComment, storageFormat);
        }
    }

    // ===================== 工具方法 =====================

    /**
     * 从 Hive JDBC URL 中提取库名。
     *
     * <p>例如 {@code jdbc:hive2://host:10000/ods?xxx=1} → {@code ods}；
     * 无库名（如 {@code jdbc:hive2://host:10000/} 或带参数前缀）时返回空串。
     */
    static String extractHiveDatabase(String jdbcUrl) {
        if (StrUtil.isBlank(jdbcUrl)) return "";
        // 取 host:port 之后、'?'/';' 之前的路径段
        String afterAuthority = jdbcUrl.replaceFirst("^jdbc:hive2://[^/]*/?", "");
        int cut = afterAuthority.length();
        for (int idx = 0; idx < afterAuthority.length(); idx++) {
            char c = afterAuthority.charAt(idx);
            if (c == '?' || c == ';' || c == '/') { cut = idx; break; }
        }
        return afterAuthority.substring(0, cut);
    }
}
