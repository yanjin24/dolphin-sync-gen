package io.github.yanjin24.dolphinscheduler;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;

/**
 * 构建 DolphinScheduler 工作流中单个 DATAX 任务所需的三段 JSON
 * （locations / taskRelationJson / taskDefinitionJson）。
 *
 * <p>取代原先在 {@code Constant} 里用固定占位 taskCode 做字符串 {@code replace} 的脆弱写法，
 * 改为直接用 JSON 对象构建，避免占位串被误替换或转义出错。
 */
public class DataXTaskBuilder {

    /** 画布坐标：{@code [{"taskCode":<code>,"x":180,"y":137}]} */
    public static String buildLocations(String taskCode) {
        JSONObject node = new JSONObject(true);
        node.set("taskCode", Long.parseLong(taskCode));
        node.set("x", 180);
        node.set("y", 137);
        return new JSONArray().put(node).toString();
    }

    /** 任务前后置关系：单任务无前置，postTaskCode 指向自身。 */
    public static String buildTaskRelationJson(String taskCode) {
        JSONObject rel = new JSONObject(true);
        rel.set("name", "");
        rel.set("preTaskCode", 0);
        rel.set("preTaskVersion", 0);
        rel.set("postTaskCode", Long.parseLong(taskCode));
        rel.set("postTaskVersion", 0);
        rel.set("conditionType", 0);
        rel.set("conditionParams", new JSONObject());
        return new JSONArray().put(rel).toString();
    }

    /**
     * 任务定义：内嵌 DataX 的 job 配置（{@code jobConfig}）到 taskParams.json。
     *
     * <p>注意 code 在此处以字符串形式写入，与历史线上格式保持一致。
     */
    public static String buildTaskDefinitionJson(String taskCode, String jobConfig) {
        JSONObject conditionResult = new JSONObject(true);
        conditionResult.set("successNode", new JSONArray());
        conditionResult.set("failedNode", new JSONArray());

        JSONObject taskParams = new JSONObject(true);
        taskParams.set("customConfig", 1);
        taskParams.set("json", jobConfig);
        taskParams.set("localParams", new JSONArray());
        taskParams.set("xms", 1);
        taskParams.set("xmx", 1);
        taskParams.set("dependence", new JSONObject());
        taskParams.set("conditionResult", conditionResult);
        taskParams.set("waitStartTimeout", new JSONObject());
        taskParams.set("switchResult", new JSONObject());

        JSONObject task = new JSONObject(true);
        task.set("code", taskCode);
        task.set("name", "datax_task");
        task.set("description", "");
        task.set("taskType", "DATAX");
        task.set("taskParams", taskParams);
        task.set("flag", "YES");
        task.set("taskPriority", "MEDIUM");
        task.set("workerGroup", "default");
        task.set("failRetryTimes", 1);
        task.set("failRetryInterval", "1");
        task.set("timeoutFlag", "CLOSE");
        task.set("timeoutNotifyStrategy", "");
        task.set("timeout", 0);
        task.set("delayTime", "0");
        task.set("environmentCode", -1);
        return new JSONArray().put(task).toString();
    }
}
