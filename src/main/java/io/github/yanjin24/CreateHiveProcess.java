package io.github.yanjin24;

import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.template.Template;
import cn.hutool.extra.template.TemplateConfig;
import cn.hutool.extra.template.TemplateUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import io.github.yanjin24.dialect.HiveDialect;
import io.github.yanjin24.dolphinscheduler.DolphinSchedulerTool;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 创建"MySQL/PostgreSQL → Hive"数据同步工作流。
 *
 * <p>与 {@link CreateProcess} 平行：reader 仍是 mysqlreader/postgresqlreader，writer 改为 DataX 的
 * {@code hdfswriter}（向 HDFS 写文件，Hive 表读取该目录）。各类可选配置（where/specified/deleteWhere/
 * cycle/批次/立即执行等）语义与 {@link CreateProcess} 一致，纯编排部分直接复用其包级静态方法。
 *
 * <pre>
 * 流程：
 *   1. resolveProjectCode（复用 CreateProcess）
 *   2. 遍历表：读源列(名 + java.sql.Types)，经 HiveDialect.toHiveType 得到 writer 列类型
 *   3. defaultFS 取必填配置 hiveDefaultFS；path 按默认 warehouse 规则推导
 *      （{hiveWarehouseDir}/{库}.db/{表}，hiveWarehouseDir 留空默认 /user/hive/warehouse）；
 *      fileType 由 hiveStorageFormat 映射（orc→orc，textfile→text）
 *   4. 渲染 job_hive.ftl，createAndScheduleWorkflow（复用 CreateProcess）
 * </pre>
 *
 * <p><b>前提</b>：目标 Hive 表须已存在（用 {@link CreateHiveTable} 生成的 DDL 手动建好），
 * 且存储格式为 orc/textfile（DataX hdfswriter 不支持 parquet）。
 * 本类不再连 Hive：原先靠 DESCRIBE FORMATTED 探测的 defaultFS/path/fileType 全部改由配置提供。
 */
public class CreateHiveProcess {

    private static final Logger log = LogManager.getLogger(CreateHiveProcess.class);

    public static void execute(SyncConfig config) {
        DolphinSchedulerTool dpTool = new DolphinSchedulerTool(config.getDpHttpUrl(), config.getDpToken());
        String dpProjectCode = CreateProcess.resolveProjectCode(config, dpTool);
        String[] tableArray = config.tableArray();

        Map<String, String> fieldMapping = CreateProcess.parseSpecified(config.getSpecified());
        List<String> taskCodeList = dpTool.getTaskCode(dpProjectCode, tableArray.length);
        Iterator<String> taskCodeIterator = taskCodeList.iterator();

        Template template = TemplateUtil
                .createEngine(new TemplateConfig("", TemplateConfig.ResourceMode.CLASSPATH))
                .getTemplate("job_hive.ftl");

        String hiveDatabase = CreateHiveTable.extractHiveDatabase(config.getOutputJdbcUrl());

        int currentTableNum = 0;
        try (Connection sourceConn = DriverManager.getConnection(
                config.getInputJdbcUrl(), config.getInputUserName(), config.getInputPassword())) {
            for (String tableName : tableArray) {
                if (StrUtil.isBlank(tableName)) continue;

                // 1. 读源表列名 + java.sql.Types（reader 列序须与 writer column 一致）
                LinkedHashMap<String, Integer> columns = getTableColumns(sourceConn, tableName);
                if (columns.isEmpty()) continue;

                // 2. 由配置推导 hdfswriter 的目标表路径等参数
                String outputTableName = config.getPrefix() + tableName + config.getSuffix();

                // 3. 渲染 job + 创建调度工作流
                String jobConfig = renderJobConfig(config, template, tableName, columns, fieldMapping,
                        buildTablePath(config.getHiveWarehouseDir(), hiveDatabase, outputTableName));
                String taskCode = taskCodeIterator.next();
                CreateProcess.createAndScheduleWorkflow(config, dpTool, dpProjectCode, taskCode,
                        tableName, jobConfig, currentTableNum);

                currentTableNum++;
                log.info("第{}张表:{}的Hive同步工作流已创建", currentTableNum, outputTableName);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // ===================== 渲染 DataX job =====================

    /** 渲染单张表的 hdfswriter job JSON。 */
    private static String renderJobConfig(SyncConfig config, Template template, String tableName,
                                          LinkedHashMap<String, Integer> columns,
                                          Map<String, String> fieldMapping, String tablePath) {
        List<String> columnList4In = new ArrayList<>();
        JSONArray hiveColumns = new JSONArray();
        for (Map.Entry<String, Integer> entry : columns.entrySet()) {
            String col = entry.getKey();
            String lowerItem = col.toLowerCase();
            // reader 列：支持 specified 直接指定值覆盖（语义同 CreateProcess）
            if (fieldMapping.containsKey(lowerItem)) {
                columnList4In.add(fieldMapping.get(lowerItem) + " as " + col);
            } else {
                columnList4In.add(col);
            }
            // writer 列：{"name":col,"type":hiveType}，与 CreateHiveTable 建表类型一致
            JSONObject colJson = new JSONObject(true);
            colJson.set("name", col);
            colJson.set("type", HiveDialect.toHiveType(entry.getValue()));
            hiveColumns.put(colJson);
        }

        String where = config.getWhere();
        String querySql = "select " + StrUtil.join(",", columnList4In) + " from " + tableName
                + (StrUtil.isEmpty(where) ? "" : " where " + where);

        Map<String, Object> param = new HashMap<>();
        param.put("readerType", CreateProcess.extractDriverType(config.getInputJdbcUrl()) + "reader");
        param.put("inputUserName", config.getInputUserName());
        param.put("inputPassword", config.getInputPassword());
        param.put("querySql", querySql);
        param.put("inputJdbcUrl", config.getInputJdbcUrl());
        param.put("errorLimit", config.getErrorLimit());
        // hdfswriter 参数：defaultFS 取必填配置 hiveDefaultFS（HA 填 nameservice）
        param.put("defaultFS", config.getHiveDefaultFS());
        param.put("fileType", resolveFileType(config.getHiveStorageFormat()));
        param.put("path", tablePath);
        param.put("fileName", tableName);
        param.put("writeMode", resolveWriteMode(config.getDeleteWhere()));
        param.put("fieldDelimiter", resolveFieldDelimiter(config.getHiveFieldDelimiter()));
        param.put("hiveColumns", hiveColumns.toString());
        Map<String, String> hadoopConfig = config.getHiveHadoopConfig();
        if (hadoopConfig != null && !hadoopConfig.isEmpty()) {
            param.put("hadoopConfig", new JSONObject(hadoopConfig).toString());
        }
        return template.render(param);
    }

    /**
     * 复用现有 deleteWhere 语义映射到 hdfswriter 的 writeMode。
     * <p>{@code truncate}/{@code 1=1} → truncate（清空目录）；空/{@code 1=2} → append；
     * 其他按条件删除 HDFS 无法实现 → append 并告警。
     */
    private static String resolveWriteMode(String deleteWhere) {
        if (deleteWhere == null) return "append";
        if (deleteWhere.equals("truncate") || deleteWhere.equals("1=1")) {
            return "truncate";
        } else if (deleteWhere.isEmpty() || deleteWhere.equals("1=2")) {
            return "append";
        } else {
            log.warn("hdfswriter 不支持按条件删除(deleteWhere={})，已退化为 append", deleteWhere);
            return "append";
        }
    }

    /** 字段分隔符：留空默认 Hive 文本默认分隔符 （ORC 下被忽略）。 */
    private static String resolveFieldDelimiter(String hiveFieldDelimiter) {
        return StrUtil.isBlank(hiveFieldDelimiter) ? "\\u0001" : hiveFieldDelimiter;
    }

    // ===================== 源库：读列名 + 类型 =====================

    /** 读取源表列名与 java.sql.Types；表不存在时返回空 Map 并跳过（对齐 CreateProcess 的 SQLState 处理）。 */
    private static LinkedHashMap<String, Integer> getTableColumns(Connection conn, String tableName) {
        LinkedHashMap<String, Integer> ret = new LinkedHashMap<>();
        try (Statement statement = conn.createStatement();
             ResultSet resultSet = statement.executeQuery("select * from " + tableName + " where 1=2")) {
            ResultSetMetaData metaData = resultSet.getMetaData();
            for (int i = 1; i <= metaData.getColumnCount(); i++) {
                ret.put(metaData.getColumnName(i), metaData.getColumnType(i));
            }
        } catch (SQLException e) {
            String state = e.getSQLState();
            if ("42P01".equals(state) || "42S02".equals(state)) { // 42P01:postgresql 42S02:MySQL
                log.warn("源表 {} 不存在，跳过", tableName);
            } else {
                throw new RuntimeException(e);
            }
        }
        return ret;
    }

    // ===================== Hive 参数推导 =====================

    /**
     * hdfswriter 的 fileType：storageFormat 留空或 orc → {@code orc}，textfile → {@code text}。
     * 其他格式（如 parquet）hdfswriter 不支持，直接报错。
     */
    static String resolveFileType(String storageFormat) {
        String format = StrUtil.isBlank(storageFormat) ? "orc" : storageFormat.trim().toLowerCase();
        if (format.equals("orc")) return "orc";
        if (format.equals("textfile")) return "text";
        throw new RuntimeException("不支持的 Hive 存储格式: " + storageFormat
                + "（hdfswriter 仅支持 orc/textfile）");
    }

    /**
     * 按默认 warehouse 规则推导目标表的 HDFS 路径：{@code {hiveWarehouseDir}/{库}.db/{表名}}。
     *
     * <p>Hive managed 表的默认 Location 即该规则；外部表或自定义 LOCATION 的表请相应调整
     * {@code hiveWarehouseDir} 或建表时显式指定 LOCATION。
     */
    static String buildTablePath(String warehouseDir, String database, String tableName) {
        String dir = StrUtil.isBlank(warehouseDir) ? "/user/hive/warehouse"
                : StrUtil.removeSuffix(warehouseDir.trim(), "/");
        StringBuilder path = new StringBuilder(dir);
        if (StrUtil.isNotBlank(database)) {
            path.append("/").append(database).append(".db");
        }
        return path.append("/").append(tableName).toString();
    }
}
