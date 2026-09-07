# dolphin-sync-gen

> 配置驱动的 DolphinScheduler + DataX 数据同步工作流生成器。

只需填写一份 `config.json`，即可**一键完成跨库数据同步的全部准备工作**：读取源库表结构、在目标库自动建表、并在 [Apache DolphinScheduler](https://dolphinscheduler.apache.org/) 中为每张表批量生成基于 DataX 的定时同步工作流（创建 → 上线 → 配置定时调度 → 按需立即执行）。

适用于「一次性把几十张表从一个库同步到另一个库」的场景，免去逐表手工建表、手工编写 DataX job、手工在 DolphinScheduler 页面上点选配置工作流的重复劳动。

## 它解决什么问题

把一批表从 A 库同步到 B 库，传统做法要对每张表分别：在 B 库手写建表 DDL → 编写 DataX job JSON → 在 DolphinScheduler 创建工作流、上线、配调度。表一多就极其繁琐且易错。

本工具把这套流程自动化：**源库元数据是唯一的真相来源**，建表 DDL、DataX job、工作流定义全部由程序按源表结构自动推导生成。

## 整体流程

```
config.json
    │  读取源库(input*) / 目标库(output*) / 表清单 / 调度参数
    ▼
读取源库表结构元数据 (列、类型、主键、唯一键、注释)
    │
    ├─[建表]──────► 在目标库执行 CREATE TABLE              (CreateTable)
    │               或生成 Hive 建表 DDL 打印，人工执行      (CreateHiveTable)
    │
    └─[建工作流]──► 渲染 DataX job (job.ftl / job_hive.ftl)
                        │
                        ▼
                调用 DolphinScheduler REST API：
                创建工作流定义 → 上线 → 配置定时调度(cron) → 按需立即执行一次
```

**运行前提**：
- 一个可访问的 DolphinScheduler 实例（提供 `dpHttpUrl` 与 `dpToken`）。
- DolphinScheduler 的执行机上已部署 DataX（生成的工作流即一个 DataX 任务）。
- 源库 / 目标库网络可达，账号具备读元数据与建表权限。

## 支持的数据源

| 角色 | 支持 |
|---|---|
| 源库 (input) | MySQL、PostgreSQL |
| 目标库 (output) | MySQL、PostgreSQL，以及 Hive（仅 `CreateHiveTable` / `CreateHiveProcess` 模式） |

方言采用插件式设计：新增一种关系型数据库，只需实现 `DialectHandler` 接口并在 `DialectFactory` 注册一行（详见[架构](#架构与扩展)）。

## 快速开始

> **构建环境**：JDK 8 或 JDK 17（编译目标为 Java 8 字节码，两者均可编译与运行）。

```bash
# 1. 编译打包（产出 fat-jar）
mvn clean package

# 2. 把配置文件放到与 jar 同级的目录（程序从运行目录 user.dir 读取 config.json）
cp config.json target/

# 3. 运行（默认无参 = 创建同步工作流）
cd target && java -jar dolphin-sync-gen-1.0.0.jar
```

构件名为 `dolphin-sync-gen-1.0.0.jar`（位于 `target/`）。配置文件固定名为 `config.json`，从**程序运行的当前目录**读取。

## 运行模式

程序通过**第一个命令行参数**切换模式，参数不区分大小写；无参时执行默认的「创建同步工作流」。

| 模式 | 命令 | 作用 |
|---|---|---|
| 创建同步工作流（默认） | `java -jar dolphin-sync-gen-1.0.0.jar` | 跨库数据同步：逐表渲染 DataX job，在 DolphinScheduler 创建工作流、上线、配置定时调度、按需立即执行 |
| 建表（含键） | `java -jar dolphin-sync-gen-1.0.0.jar CreateTable` | 在目标库按源表结构自动建表，**含**主键、唯一键、注释、NOT NULL、默认值 |
| 建表（不含键） | `java -jar dolphin-sync-gen-1.0.0.jar CreateTableNoKey` | 同上，但**不含**主键、唯一键 |
| 下线项目 | `java -jar dolphin-sync-gen-1.0.0.jar <projectCode>` | 参数为纯数字时，下线该 projectCode 下所有工作流（便于后续在 DolphinScheduler 中删除） |
| 建 Hive 表 | `java -jar dolphin-sync-gen-1.0.0.jar CreateHiveTable` | 按 MySQL/PG 源表结构**生成 Hive 建表 DDL 打印到控制台（不执行）**，人工复制到 beeline/hive 执行。`input*` 填源库，`output*` 填 Hive（`jdbc:hive2://host:10000/库`，仅用于解析库名）。列类型仅 `bigint`/`double`/`string` 三种，不含任何约束 |
| RDBMS→Hive 同步 | `java -jar dolphin-sync-gen-1.0.0.jar CreateHiveProcess` | 创建「MySQL/PG → Hive」的 DataX(`hdfswriter`) 同步工作流。`input*` 填源库，`output*` 填 Hive（仅用于解析库名）。需配置 `hiveDefaultFS`（必填）、`hiveWarehouseDir`（可选）等 Hive 参数 |

**典型使用顺序**：
1. 同库/跨 RDBMS 同步：先 `CreateTable`（或 `CreateTableNoKey`）建好目标表 → 再无参运行创建同步工作流。
2. 同步到 Hive：先 `CreateHiveTable` 生成建表 DDL（人工执行）→ 再 `CreateHiveProcess` 创建同步工作流。
   - 前提：目标 Hive 表必须**已存在**且存储格式为 `orc` 或 `textfile`（DataX `hdfswriter` 不支持 `parquet`）。
   - `hiveDefaultFS` 必填（HA 集群填 nameservice，如 `hdfs://nameservice1`，可附 `hiveHadoopConfig`）；HDFS 路径按默认 warehouse 规则推导（`/user/hive/warehouse/库.db/表`），外部表或自定义 LOCATION 可用 `hiveWarehouseDir` 调整。

## 配置项说明（config.json）

> 下方示例中的地址 / token / 账号均为占位符，请替换为实际值。`//` 注释仅作说明，标准 JSON 不支持注释，实际使用请去掉或确保解析器容错。

```jsonc
{
  // ── DolphinScheduler ──
  "dpHttpUrl": "http://<ds-host>:12345",   // DolphinScheduler 访问地址（不含 /dolphinscheduler 后缀）
  "dpToken":   "<your-token>",             // DolphinScheduler API token
  "dpTenant":  "<tenant>",                 // 工作流运行租户
  "dpProjectName": "postgresql2mysql",     // 项目名；dpProjectCode 为空时按此名创建项目
  "dpProjectCode": "",                     // 已有项目编码；填了则直接用，不再新建项目

  // ── 源库 (input) ──
  "inputJdbcUrl":  "jdbc:postgresql://<host>:5432/db?currentSchema=my_schema", // PG 非 public schema 需指定
  "inputUserName": "postgres",
  "inputPassword": "postgres",

  // ── 目标库 (output) ──
  "outputJdbcUrl":  "jdbc:mysql://<host>:3306/test?characterEncoding=UTF-8",
  "outputUserName": "root",
  "outputPassword": "root",

  // ── 同步参数 ──
  "errorLimit": 0,                  // 脏数据条数上限（主键/唯一键重复即脏数据），超过则任务失败
  "specified":  "99 as id,'张三' as name", // 直接指定值字段，逗号分隔，形式 "值 as 列名"
  "where":      "sex='男'",         // 源表过滤条件，留空则全量同步
  "deleteWhere":"1=1",              // 同步前对目标表的清理策略（见下）
  "cycle":      "minute:15",        // 调度周期（见下）
  "hourBegin":  0,                  // 任务队列起始小时
  "minuteBegin":10,                 // 任务队列起始分钟
  "tableDispatchBatchSize":     2,  // 同一批次的任务数量
  "tableDispatchBatchInterval": 3,  // 批次间隔（分钟）—— 多表错峰调度，避免同时启动
  "executeImmediately": "yes",      // "yes"/"true" 则创建后立即执行一次，否则仅按调度执行
  "prefix": "o_full_",              // 目标表名前缀
  "suffix": "",                     // 目标表名后缀
  "tables": "test1,ff,table1,sys_user", // 待处理表名，逗号分隔

  // ── Hive 专用（仅 CreateHiveTable / CreateHiveProcess）──
  "hiveStorageFormat":  "",         // Hive 存储格式(STORED AS)，留空默认 orc，可填 parquet/textfile
                                    //   注意：向 Hive 同步数据仅支持 orc/textfile，parquet 无法写入
  "hiveDefaultFS":      "",         // 仅 CreateHiveProcess 必填：HDFS defaultFS，HA 场景填 nameservice(如 hdfs://nameservice1)
  "hiveWarehouseDir":   "",         // 仅 CreateHiveProcess：warehouse 根目录，路径推导 {dir}/{库}.db/{表}；留空默认 /user/hive/warehouse
  "hiveFieldDelimiter": "",         // 仅 CreateHiveProcess：hdfswriter 字段分隔符，留空默认 Hive 文本默认分隔符（ORC 表忽略）
  "hiveHadoopConfig":   {}          // 仅 CreateHiveProcess：hdfswriter 的 hadoopConfig，HA 场景填 dfs.nameservices/dfs.ha.namenodes.* 等；为空则省略
}
```

**几个参数的详细语义：**

- **`deleteWhere`**（同步前对目标表的清理）：
  - `"truncate"` 或 `"1=1"` → 清空目标表（`truncate table`）
  - `""`（空串）或 `"1=2"` → 不删除任何数据
  - 其他内容 → 作为删除过滤条件（`delete from 表 where <deleteWhere>`）
  - （Hive 同步时映射为 `hdfswriter` 的 `writeMode`：清空→`truncate`，不删→`append`，按条件删除无法实现→退化为 `append` 并告警）

- **`cycle`**（调度周期 cron）：
  - 留空 → 每天一次
  - `"minute:15"` → 每 15 分钟一次
  - `"hour:2"` → 每 2 小时一次

- **批次错峰**：多张表不会同时启动，而是按 `tableDispatchBatchSize` 分批、批次间隔 `tableDispatchBatchInterval` 分钟，从 `hourBegin:minuteBegin` 起依次错开，降低源库瞬时压力。

- **`specified`**：用于给某些列直接赋固定值而非取自源表，形式 `值 as 列名`，多个用逗号分隔，如 `99 as id,'张三' as name`。

## 注意事项

- `tables` 中不存在的源表会被自动跳过（仅告警，不中断其余表）。
- 脏数据（主键/唯一键重复）条数超过 `errorLimit` 时对应 DataX 任务失败。
- 同步前的清空/删除由 `deleteWhere` 控制，**配置 `truncate`/`1=1` 会清空目标表**，请谨慎。
- 同步到 Hive 仅支持 `orc` / `textfile` 存储格式，`parquet` 表无法写入（建表时即默认 `orc`）。
- 工作流执行策略为「串行丢弃」(`SERIAL_DISCARD`)：同一工作流上一次未跑完时，新触发会被丢弃。
