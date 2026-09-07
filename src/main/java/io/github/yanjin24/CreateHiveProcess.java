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

import java.net.URI;
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
 *   3. 连 Hive 对 库.表 执行 DESCRIBE FORMATTED，解析出 HDFS path 与 fileType（parquet 不支持→跳过）
 *   4. 渲染 job_hive.ftl，createAndScheduleWorkflow（复用 CreateProcess）
 * </pre>
 *
 * <p><b>前提</b>：目标 Hive 表须已存在（先跑 {@link CreateHiveTable}），且存储格式为 orc/textfile
 * （DataX hdfswriter 不支持 parquet）。
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
                config.getInputJdbcUrl(), config.getInputUserName(), config.getInputPassword());
             Connection hiveConn = DriverManager.getConnection(
                     config.getOutputJdbcUrl(), config.getOutputUserName(), config.getOutputPassword())) {
            for (String tableName : tableArray) {
                if (StrUtil.isBlank(tableName)) continue;

                // 1. 读源表列名 + java.sql.Types（reader 列序须与 writer column 一致）
                LinkedHashMap<String, Integer> columns = getTableColumns(sourceConn, tableName);
                if (columns.isEmpty()) continue;

                // 2. 连 Hive 探测目标表的 HDFS path 与 fileType
                String outputTableName = config.getPrefix() + tableName + config.getSuffix();
                HiveTarget target = describeHiveTarget(hiveConn, hiveDatabase, outputTableName);
                if (target == null) continue; // 表不存在 / parquet 等不支持的情况已在内部告警

                // 3. 渲染 job + 创建调度工作流
                String jobConfig = renderJobConfig(config, template, tableName, columns, fieldMapping, target);
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
                                          Map<String, String> fieldMapping, HiveTarget target) {
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
        // hdfswriter 参数：defaultFS 优先用 config（HA 场景由用户控制），留空则取自 DESCRIBE 的 Location
        String defaultFS = StrUtil.isNotBlank(config.getHiveDefaultFS())
                ? config.getHiveDefaultFS() : target.defaultFS;
        param.put("defaultFS", defaultFS);
        param.put("fileType", target.fileType);
        param.put("path", target.path);
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

    // ===================== Hive：DESCRIBE 探测 path / fileType =====================

    /**
     * 对 {@code 库.表} 执行 {@code DESCRIBE FORMATTED}，解析 HDFS path、defaultFS、fileType。
     *
     * @return 探测结果；表不存在或存储格式不被 hdfswriter 支持（parquet）时返回 {@code null}（已告警）
     */
    private static HiveTarget describeHiveTarget(Connection hiveConn, String database, String tableName) {
        String qualified = (StrUtil.isBlank(database) ? "" : "`" + database + "`.") + "`" + tableName + "`";
        String location = null;
        String inputFormat = null;
        try (Statement stmt = hiveConn.createStatement();
             ResultSet rs = stmt.executeQuery("DESCRIBE FORMATTED " + qualified)) {
            while (rs.next()) {
                String key = rs.getString(1);
                if (key == null) continue;
                key = key.trim();
                if (key.startsWith("Location")) {
                    location = StrUtil.trimToEmpty(rs.getString(2));
                } else if (key.startsWith("InputFormat")) {
                    inputFormat = StrUtil.trimToEmpty(rs.getString(2));
                }
            }
        } catch (SQLException e) {
            log.warn("Hive 表 {} DESCRIBE 失败（可能不存在），跳过: {}", qualified, e.getMessage());
            return null;
        }

        if (StrUtil.isBlank(location)) {
            log.warn("Hive 表 {} 未解析到 Location，跳过", qualified);
            return null;
        }
        String fileType;
        try {
            fileType = mapInputFormatToFileType(inputFormat);
        } catch (IllegalStateException e) {
            log.warn("Hive 表 {} {}，跳过", qualified, e.getMessage());
            return null;
        }
        return new HiveTarget(parseDefaultFsFromLocation(location), parsePathFromLocation(location), fileType);
    }

    /** 把 Hive 表的 InputFormat 类名映射为 hdfswriter 的 fileType。parquet/未知格式抛 {@link IllegalStateException}。 */
    static String mapInputFormatToFileType(String inputFormat) {
        if (inputFormat == null) {
            throw new IllegalStateException("未解析到 InputFormat");
        }
        String lower = inputFormat.toLowerCase();
        if (lower.contains("orc")) {
            return "orc";
        } else if (lower.contains("text")) {
            return "text";
        } else if (lower.contains("parquet")) {
            throw new IllegalStateException("存储格式为 parquet，DataX hdfswriter 不支持（请用 orc 或 textfile 重建表）");
        }
        throw new IllegalStateException("不支持的存储格式 InputFormat=" + inputFormat);
    }

    /** 从 Hive Location URI 中取 HDFS 路径部分（去掉 scheme 与 authority）。 */
    static String parsePathFromLocation(String location) {
        try {
            String path = new URI(location).getPath();
            return StrUtil.isBlank(path) ? location : path;
        } catch (Exception e) {
            // 退化：手工截取 authority 之后的路径
            int schemeIdx = location.indexOf("://");
            if (schemeIdx < 0) return location;
            int pathIdx = location.indexOf('/', schemeIdx + 3);
            return pathIdx < 0 ? "/" : location.substring(pathIdx);
        }
    }

    /** 从 Hive Location URI 中取 {@code scheme://authority} 作为 defaultFS（HA 下即 nameservice）。 */
    static String parseDefaultFsFromLocation(String location) {
        try {
            URI uri = new URI(location);
            if (uri.getScheme() != null && uri.getAuthority() != null) {
                return uri.getScheme() + "://" + uri.getAuthority();
            }
        } catch (Exception ignored) {
            // 落到下面的退化逻辑
        }
        int schemeIdx = location.indexOf("://");
        if (schemeIdx < 0) return "";
        int pathIdx = location.indexOf('/', schemeIdx + 3);
        return pathIdx < 0 ? location : location.substring(0, pathIdx);
    }

    /** DESCRIBE 探测结果。 */
    private static class HiveTarget {
        final String defaultFS;
        final String path;
        final String fileType;

        HiveTarget(String defaultFS, String path, String fileType) {
            this.defaultFS = defaultFS;
            this.path = path;
            this.fileType = fileType;
        }
    }
}
