package io.github.yanjin24;

import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.template.Template;
import cn.hutool.extra.template.TemplateConfig;
import cn.hutool.extra.template.TemplateUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import io.github.yanjin24.dolphinscheduler.DataXTaskBuilder;
import io.github.yanjin24.dolphinscheduler.DolphinSchedulerTool;
import io.github.yanjin24.entity.DPProcessDefinition;
import io.github.yanjin24.entity.DPProject;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class CreateProcess {
    private static final Logger log = LogManager.getLogger(CreateProcess.class);

    public static void execute(SyncConfig config) {
        DolphinSchedulerTool dpTool = new DolphinSchedulerTool(config.getDpHttpUrl(), config.getDpToken());
        String dpProjectCode = resolveProjectCode(config, dpTool);
        String[] tableArray = config.tableArray();

        Map<String, String> fieldMapping = parseSpecified(config.getSpecified());
        List<String> taskCodeList = dpTool.getTaskCode(dpProjectCode, tableArray.length);
        Iterator<String> taskCodeIterator = taskCodeList.iterator();

        // 模板引擎与源库连接在循环外创建一次，避免每张表重复初始化
        Template template = TemplateUtil
                .createEngine(new TemplateConfig("", TemplateConfig.ResourceMode.CLASSPATH))
                .getTemplate("job.ftl");

        int currentTableNum = 0;
        try (Connection sourceConn = DriverManager.getConnection(
                config.getInputJdbcUrl(), config.getInputUserName(), config.getInputPassword())) {
            for (String tableName : tableArray) {
                List<String> tableFields = getTableFields(sourceConn, tableName);
                if (tableFields.isEmpty()) continue;

                String jobConfig = renderJobConfig(config, template, tableName, tableFields, fieldMapping);
                String taskCode = taskCodeIterator.next();
                createAndScheduleWorkflow(config, dpTool, dpProjectCode, taskCode, tableName, jobConfig, currentTableNum);

                currentTableNum++;
                log.info("第{}张表:{}工作流已创建", currentTableNum, config.getPrefix() + tableName + config.getSuffix());
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** 取已有 projectCode，没有则在 DolphinScheduler 中创建项目。包级可见以便 CreateHiveProcess 复用。 */
    static String resolveProjectCode(SyncConfig config, DolphinSchedulerTool dpTool) {
        if (StrUtil.isNotBlank(config.getDpProjectCode())) {
            return config.getDpProjectCode();
        }
        DPProject dpProject = new DPProject();
        dpProject.setProjectName(config.getDpProjectName());
        return dpTool.createProject(dpProject);
    }

    /** 解析 "99 as id,'张三' as name" 形式的直接指定值字段，返回 字段名(小写)->值 的映射。包级可见以便 CreateHiveProcess 复用。 */
    static Map<String, String> parseSpecified(String specified) {
        Map<String, String> fieldMapping = new HashMap<>();
        if (StrUtil.isBlank(specified)) return fieldMapping;
        for (String expr : specified.split(",")) {
            String[] parts = expr.trim().split("\\s+[Aa][Ss]\\s+", 2);
            if (parts.length == 2) {
                fieldMapping.put(parts[1].trim().toLowerCase(), parts[0].trim());
            }
        }
        return fieldMapping;
    }

    /** 渲染单张表的 DataX job JSON。 */
    private static String renderJobConfig(SyncConfig config, Template template, String tableName,
                                          List<String> tableFields, Map<String, String> fieldMapping) {
        List<String> columnList4In = new ArrayList<>();
        List<String> columnList4Out = new ArrayList<>();
        for (String item : tableFields) {
            String lowerItem = item.toLowerCase();
            if (fieldMapping.containsKey(lowerItem)) {
                columnList4In.add(fieldMapping.get(lowerItem) + " as " + item);
            } else {
                columnList4In.add(item);
            }
            columnList4Out.add("\"" + item + "\"");
        }

        String outputTableName = config.getPrefix() + tableName + config.getSuffix();
        String where = config.getWhere();
        String querySql = "select " + StrUtil.join(",", columnList4In) + " from " + tableName
                + (StrUtil.isEmpty(where) ? "" : " where " + where);

        Map<String, Object> param = new HashMap<>();
        param.put("readerType", extractDriverType(config.getInputJdbcUrl()) + "reader");
        param.put("inputUserName", config.getInputUserName());
        param.put("inputPassword", config.getInputPassword());
        param.put("querySql", querySql);
        param.put("inputJdbcUrl", config.getInputJdbcUrl());
        param.put("writerType", extractDriverType(config.getOutputJdbcUrl()) + "writer");
        param.put("outputUserName", config.getOutputUserName());
        param.put("outputPassword", config.getOutputPassword());
        param.put("outputColumn", StrUtil.join(",", columnList4Out));
        param.put("preSql1", buildPreSql(config.getDeleteWhere(), outputTableName));
        param.put("outputJdbcUrl", config.getOutputJdbcUrl());
        param.put("table", outputTableName);
        param.put("errorLimit", config.getErrorLimit());
        return template.render(param);
    }

    /** 根据 deleteWhere 计算同步前置 SQL；返回 null 表示不删除任何数据。 */
    private static String buildPreSql(String deleteWhere, String outputTableName) {
        if (deleteWhere.equals("truncate") || deleteWhere.equals("1=1")) {
            return "truncate table " + outputTableName;
        } else if (deleteWhere.isEmpty() || deleteWhere.equals("1=2")) {
            return null;
        } else {
            return "delete from " + outputTableName + " where " + deleteWhere;
        }
    }

    /** 创建工作流定义、上线、配置定时调度，并按需立即执行一次。包级可见以便 CreateHiveProcess 复用。 */
    static void createAndScheduleWorkflow(SyncConfig config, DolphinSchedulerTool dpTool,
                                                  String dpProjectCode, String taskCode, String tableName,
                                                  String jobConfig, int currentTableNum) {
        String outputTableName = config.getPrefix() + tableName + config.getSuffix();
        DPProcessDefinition dpProcessDefinition = new DPProcessDefinition(config.getDpTenant());
        dpProcessDefinition.setName(outputTableName);
        dpProcessDefinition.setLocations(DataXTaskBuilder.buildLocations(taskCode));
        dpProcessDefinition.setTaskRelationJson(DataXTaskBuilder.buildTaskRelationJson(taskCode));
        dpProcessDefinition.setTaskDefinitionJson(DataXTaskBuilder.buildTaskDefinitionJson(taskCode, jobConfig));
        dpProcessDefinition.setExecutionType("SERIAL_DISCARD"); // 串行丢弃

        dpTool.createProcessDefinition(dpProjectCode, dpProcessDefinition);
        dpTool.release(dpProjectCode, dpProcessDefinition.getCode(), "ONLINE");
        String scheduleId = dpTool.createSchedule(dpProjectCode, dpProcessDefinition.getCode(),
                getCron(config, currentTableNum), null);
        dpTool.releaseSchedule(dpProjectCode, scheduleId, "online");
        if (StrUtil.equalsAnyIgnoreCase(config.getExecuteImmediately(), "yes", "true")) {
            dpTool.executeOnce(dpProjectCode, dpProcessDefinition.getCode());
        }
    }

    static String getCron(SyncConfig config, int currentTableNum) {
        String cron = "0 a b * * ? *";
        int total = (currentTableNum / config.getTableDispatchBatchSize()) * config.getTableDispatchBatchInterval();
        int hour = (total / 60) + config.getHourBegin();
        int minute = (total % 60) + config.getMinuteBegin();
        String cycle = config.getCycle();
        if (cycle.contains("hour")) {
            String hourCycle = cycle.substring("hour:".length());
            return cron.replace("a", minute + "").replace("b", hour + "/" + hourCycle);
        } else if (cycle.contains("minute")) {
            String minuteCycle = cycle.substring("minute:".length());
            return cron.replace("a", minute + "/" + minuteCycle).replace("b", hour + "/1");
        }
        return cron.replace("a", minute + "").replace("b", hour + "");
    }

    /** 读取源表字段名；表不存在时返回空列表并跳过。复用调用方传入的连接。 */
    private static List<String> getTableFields(Connection conn, String tableName) {
        List<String> ret = new ArrayList<>();
        if (StrUtil.isBlank(tableName)) return ret;
        try (Statement statement = conn.createStatement();
             ResultSet resultSet = statement.executeQuery("select * from " + tableName + " where 1=2")) {
            ResultSetMetaData metaData = resultSet.getMetaData();
            for (int i = 1; i <= metaData.getColumnCount(); i++) {
                ret.add(metaData.getColumnName(i));
            }
        } catch (SQLException e) {
            String state = e.getSQLState();
            if ("42P01".equals(state) || "42S02".equals(state)) { // 42P01:postgresql 42S02:MySQL
                log.warn("表 {} 不存在，跳过", tableName);
            } else {
                throw new RuntimeException(e);
            }
        }
        return ret;
    }

    /** 从 JDBC URL 提取驱动类型，如 jdbc:postgresql://... → "postgresql"。包级可见以便 CreateHiveProcess 复用。 */
    static String extractDriverType(String jdbcUrl) {
        return jdbcUrl.replaceAll("jdbc:([^:]+):.*", "$1");
    }
}
