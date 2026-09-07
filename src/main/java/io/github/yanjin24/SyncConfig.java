package io.github.yanjin24;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import lombok.Data;

/**
 * 统一的配置对象，集中解析 config.json，避免在各入口类里散落 {@code config.getStr(...)} 调用。
 *
 * <p>所有配置项的 key 只在这里出现一次，新增/重命名配置只需改动本类。
 */
@Data
public class SyncConfig {
    // ── DolphinScheduler ──
    private String dpHttpUrl;
    private String dpToken;
    private String dpTenant;
    private String dpProjectName;
    private String dpProjectCode;
    // ── 源库 ──
    private String inputJdbcUrl;
    private String inputUserName;
    private String inputPassword;
    // ── 目标库 ──
    private String outputJdbcUrl;
    private String outputUserName;
    private String outputPassword;
    // ── 同步参数 ──
    private int errorLimit;
    private String specified;
    private String where;
    private String deleteWhere;
    private String cycle;
    private int hourBegin;
    private int minuteBegin;
    private int tableDispatchBatchSize;
    private int tableDispatchBatchInterval;
    private String executeImmediately;
    private String prefix;
    private String suffix;
    private String tables;
    /** Hive 存储格式（STORED AS），留空默认 orc，可填 orc/parquet/textfile。Hive 相关模式共用。 */
    private String hiveStorageFormat;
    /** Hive 同步：HDFS defaultFS，HA 场景填 nameservice（如 hdfs://nameservice1）。CreateHiveProcess 必填。 */
    private String hiveDefaultFS;
    /** Hive 同步：warehouse 根目录，用于推导目标表 HDFS 路径 {dir}/{库}.db/{表}；留空默认 /user/hive/warehouse。 */
    private String hiveWarehouseDir;
    /** Hive 同步：hdfswriter 字段分隔符，留空默认 Hive 文本默认分隔符 （ORC 下忽略）。 */
    private String hiveFieldDelimiter;
    /** Hive 同步：hdfswriter 的 hadoopConfig，HA 场景填 dfs.nameservices 等；为空则省略该参数。 */
    private java.util.Map<String, String> hiveHadoopConfig;

    /** 读取并解析配置文件，文件不存在或为空时抛出异常。 */
    public static SyncConfig load(String configPath) {
        String configStr = FileUtil.readUtf8String(configPath);
        if (StrUtil.isBlank(configStr)) {
            throw new RuntimeException("读取配置文件失败，configPath:" + configPath);
        }
        return new JSONObject(configStr).toBean(SyncConfig.class);
    }

    /** 配置的表名数组（按逗号切分）。 */
    public String[] tableArray() {
        return tables.split(",");
    }
}
