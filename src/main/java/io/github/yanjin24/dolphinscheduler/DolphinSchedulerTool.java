package io.github.yanjin24.dolphinscheduler;

import cn.hutool.core.date.DateUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpUtil;
import cn.hutool.http.Method;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import io.github.yanjin24.entity.DPDataSource;
import io.github.yanjin24.entity.DPProcessDefinition;
import io.github.yanjin24.entity.DPProject;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DolphinSchedulerTool {
    private static final Logger log = LogManager.getLogger(DolphinSchedulerTool.class);

    private final String dpHttpUrl;
    private final String dpToken;
    private final int timeOut = 10000;

    public DolphinSchedulerTool(String dpHttpUrl, String dpToken) {
        this.dpHttpUrl = dpHttpUrl;
        this.dpToken = dpToken;
    }

    // ===================== 通用 helper =====================

    /** 拼接完整 API 地址：dpHttpUrl + "/dolphinscheduler" + path。 */
    private String url(String path) {
        return this.dpHttpUrl + "/dolphinscheduler" + path;
    }

    /** 仅带 token 的请求头（GET / DELETE 用）。 */
    private Map<String, String> createHeader() {
        Map<String, String> headers = new HashMap<>();
        headers.put("token", this.dpToken);
        return headers;
    }

    private Map<String, String> createHeader(String contentType) {
        Map<String, String> headers = createHeader();
        headers.put("Content-Type", contentType);
        return headers;
    }

    /** 表单提交头（绝大多数 POST/PUT 用）。 */
    private Map<String, String> formHeader() {
        return createHeader("application/x-www-form-urlencoded");
    }

    /** JSON body 提交头。 */
    private Map<String, String> jsonHeader() {
        return createHeader("application/json");
    }

    /** 统一执行：发请求 → 解析 body → checkRetCode → 返回整个响应 JSON。 */
    private JSONObject send(HttpRequest request, String errorMsg) {
        JSONObject responseJson = new JSONObject(request.timeout(this.timeOut).execute().body());
        this.checkRetCode(responseJson, errorMsg);
        return responseJson;
    }

    public void checkRetCode(JSONObject retObj, String errorMsgPrefix) {
        Integer code = retObj.getInt("code");
        if (code == null || 0 != code) {
            throw new RuntimeException(errorMsgPrefix + "失败！" + retObj);
        }
    }

    // ===================== 项目 =====================

    public String createProject(DPProject dpProject) {
        Map<String, Object> body = new HashMap<>();
        body.put("projectName", dpProject.getProjectName());
        JSONObject responseJson = send(
                HttpUtil.createPost(url("/projects")).addHeaders(formHeader()).form(body), "创建项目");
        return responseJson.getJSONObject("data").getStr("code");
    }

    // ===================== 数据源 =====================

    public String createDataSource(DPDataSource dpDataSource) {
        send(HttpUtil.createPost(url("/datasources")).addHeaders(jsonHeader())
                .body(new JSONObject(dpDataSource).toString()), "创建数据源");
        int pageNo = 1;
        int pageSize = 1;
        String searchVal = dpDataSource.getName();
        JSONObject queryResponseJson = send(HttpUtil.createGet(
                url("/datasources?pageNo=" + pageNo + "&pageSize=" + pageSize + "&searchVal=" + searchVal))
                .addHeaders(createHeader()), "查询数据源编码");
        JSONObject queryData = queryResponseJson.getJSONObject("data");
        JSONArray totalList = queryData.getJSONArray("totalList");
        if (totalList.isEmpty()) {
            throw new RuntimeException("DolphinScheduler查询数据源ID失败！");
        } else {
            return totalList.getJSONObject(0).getStr("id");
        }
    }

    public List<String> listWorker() {
        List<String> ret = new ArrayList<>();
        JSONObject dataRet = send(
                HttpUtil.createGet(url("/monitor/workers")).addHeaders(createHeader()), "查询执行机器列表");
        JSONArray data = dataRet.getJSONArray("data");
        data.forEach((item) -> {
            JSONObject hostItem = (JSONObject) item;
            ret.add(hostItem.getStr("host"));
        });
        return ret;
    }

    // ===================== 工作流定义 =====================

    public void createProcessDefinition(String projectCode, DPProcessDefinition dpProcessDefinition) {
        JSONObject dataRet = send(HttpUtil.createPost(url("/projects/" + projectCode + "/process-definition"))
                .addHeaders(formHeader()).form(new JSONObject(dpProcessDefinition)), "创建工作流");
        JSONObject data = dataRet.getJSONObject("data");
        String code = data.getStr("code");
        Integer version = data.getInt("version");
        dpProcessDefinition.setCode(code);
        dpProcessDefinition.setVersion(version);
    }

    public void updateProcessDefinition(String projectCode, String processDefinitionCode, DPProcessDefinition dpProcessDefinition) {
        JSONObject dataRet = send(HttpUtil.createPost(url("/projects/" + projectCode + "/process-definition/" + processDefinitionCode))
                .addHeaders(formHeader()).form(new JSONObject(dpProcessDefinition)), "更新工作流");
        JSONObject data = dataRet.getJSONObject("data");
        String code = data.getStr("code");
        Integer version = data.getInt("version");
        dpProcessDefinition.setCode(code);
        dpProcessDefinition.setVersion(version);
    }

    public List<String> getTaskCode(String projectCode, int genNum) {
        ArrayList<String> result = new ArrayList<>();
        int batch = (genNum / 100) + 1;
        for (int i = 1; i <= batch; i++) {
            int batchNum = 100;
            if (i == batch) {
                batchNum = genNum % 100;
            }
            JSONObject dataRet = send(HttpUtil.createGet(
                    url("/projects/" + projectCode + "/task-definition/gen-task-codes?genNum=" + batchNum))
                    .addHeaders(createHeader()), "生成工作流坐标");
            JSONArray data = dataRet.getJSONArray("data");
            result.addAll(data.toList(String.class));
        }
        return result;
    }

    public void delProcessDefinitionVersion(String projectCode, String processDefinitionCode, String versionId) {
        send(HttpUtil.createRequest(Method.DELETE,
                url("/projects/" + projectCode + "/process-definition/" + processDefinitionCode + "/versions/" + versionId))
                .addHeaders(createHeader()), "删除工作流版本");
    }

    public Map<String, String> queryList(String projectCode) {
        Map<String, String> processList = new LinkedHashMap<>();
        int pageNo = 1;
        while (true) {
            String requestUrl = url("/projects/" + projectCode + "/process-definition") + "?pageSize=10&pageNo=" + pageNo;
            JSONObject dataRet = send(HttpUtil.createGet(requestUrl).addHeaders(createHeader()), "查询工作流定义列表");

            JSONObject data = dataRet.getJSONObject("data");
            if (data == null) {
                break;
            }
            JSONArray totalList = data.getJSONArray("totalList");
            if (totalList == null) {
                break;
            }
            for (Object item : totalList) {
                JSONObject workflow = (JSONObject) item;
                processList.put(workflow.getStr("code"), workflow.getStr("name"));
            }
            int totalPage = data.getInt("totalPage");
            if (pageNo >= totalPage) {
                break;
            }
            pageNo++;
        }
        return processList;
    }

    public int release(String projectCode, String processDefinitionCode, String releaseState) {
        JSONObject body = new JSONObject();
        body.set("releaseState", releaseState);
        JSONObject dataRet = send(HttpUtil.createPost(
                url("/projects/" + projectCode + "/process-definition/" + processDefinitionCode + "/release"))
                .addHeaders(formHeader()).form(body), "工作流上下线");
        return dataRet.getInt("code");
    }

    // ===================== 调度 =====================

    public void executeOnce(String projectCode, String processDefinitionCode) {
        JSONObject body = new JSONObject();
        body.set("processDefinitionCode", processDefinitionCode);
        body.set("failureStrategy", "END");
        body.set("warningType", "NONE");
        body.set("execType", "START_PROCESS");
        body.set("taskDependType", "TASK_POST");
        body.set("complementDependentMode", "OFF_MODE");
        body.set("runMode", "RUN_MODE_SERIAL");
        body.set("processInstancePriority", "MEDIUM");
        body.set("workerGroup", "default");
        body.set("dryRun", 0);
        body.set("scheduleTime", "");
        send(HttpUtil.createPost(url("/projects/" + projectCode + "/executors/start-process-instance"))
                .addHeaders(formHeader()).form(body), "执行一次");
    }

    public String createSchedule(String projectCode, String processDefinitionCode, String cron, Date startTime) {
        JSONObject body = new JSONObject();
        body.set("schedule", this.getScheduleJson(cron, startTime).toString());
        body.set("failureStrategy", "CONTINUE");
        body.set("processInstancePriority", "MEDIUM");
        body.set("warningGroupId", "0");
        body.set("workerGroup", "default");
        body.set("warningType", "NONE");
        body.set("environmentCode", "");
        body.set("processDefinitionCode", processDefinitionCode);
        JSONObject dataRet = send(HttpUtil.createPost(url("/projects/" + projectCode + "/schedules"))
                .addHeaders(formHeader()).form(body), "创建调度器");
        return dataRet.getJSONObject("data").getStr("id");
    }

    public String updateSchedule(String projectCode, String scheduleId, String cron, Date startTime) {
        JSONObject body = new JSONObject();
        body.set("schedule", this.getScheduleJson(cron, startTime).toString());
        body.set("failureStrategy", "CONTINUE");
        body.set("processInstancePriority", "MEDIUM");
        body.set("warningGroupId", "0");
        body.set("workerGroup", "default");
        body.set("warningType", "NONE");
        body.set("environmentCode", "");
        JSONObject dataRet = send(HttpUtil.createRequest(Method.PUT, url("/projects/" + projectCode + "/schedules/" + scheduleId))
                .addHeaders(formHeader()).form(body), "更新调度器");
        return dataRet.getJSONObject("data").getStr("id");
    }

    public int releaseSchedule(String projectCode, String scheduleId, String releaseState) {
        JSONObject dataRet = send(HttpUtil.createPost(
                url("/projects/" + projectCode + "/schedules/" + scheduleId + "/" + releaseState))
                .addHeaders(formHeader()), "调度上下线");
        return dataRet.getInt("code");
    }

    private JSONObject getScheduleJson(String cron, Date startTime) {
        JSONObject json = new JSONObject(true);
        if (startTime == null) {
            json.set("startTime", DateUtil.formatDateTime(new Date()));
        } else {
            json.set("startTime", DateUtil.formatDateTime(startTime));
        }
        json.set("endTime", "2100-01-01 00:00:00");
        json.set("crontab", cron);
        json.set("timezoneId", "Asia/Shanghai");
        return json;
    }

    // ===================== 实例与日志 =====================

    public JSONObject queryProcessInstanceListPaging(String projectCode, String processDefineCode, String page, String limit, String timeStart, String timeEnd, String status, String host) {
        JSONObject dataRet = send(HttpUtil.createGet(url("/projects/" + projectCode + "/process-instances?pageNo=" + page + "&pageSize=" + limit + "&host=" + host + "&startDate=" + timeStart + "&endDate=" + timeEnd + "&status=" + status + "&processDefineCode=" + processDefineCode))
                .addHeaders(createHeader()), "分页查询工作流实例");
        return dataRet.getJSONObject("data");
    }

    public JSONObject queryAllProcessInstance(String projectCode, int pageSize) {
        JSONObject dataRet = send(HttpUtil.createGet(url("/projects/" + projectCode + "/process-instances?searchVal=&pageSize=" + pageSize + "&pageNo=1&host=&stateType=&startDate=&endDate=&executorName="))
                .addHeaders(createHeader()), "查询全部工作流实例");
        return dataRet.getJSONObject("data");
    }

    public void reRunInstance(String projectCode, String processInstanceId) {
        JSONObject body = new JSONObject();
        body.set("processInstanceId", processInstanceId);
        body.set("executeType", "REPEAT_RUNNING");
        send(HttpUtil.createPost(url("/projects/" + projectCode + "/executors/execute"))
                .addHeaders(formHeader()).form(body), "工作流实例重跑");
    }

    public String getInstanceLog(int limit, long taskInstanceId) {
        JSONObject returnJson = send(HttpUtil.createGet(
                url("/log/detail?taskInstanceId=" + taskInstanceId + "&skipLineNum=0&limit=" + limit))
                .addHeaders(createHeader()), "获取任务实例日志详情出错！");
        return returnJson.getStr("data");
    }

    public JSONObject getDPDispatchInstanceDetail(String projectCode, String instanceId) {
        JSONObject returnJson = send(HttpUtil.createGet(
                url("/projects/" + projectCode + "/process-instances/" + instanceId + "/tasks?processInstanceId=" + instanceId))
                .addHeaders(createHeader()), "获取工作流实例详情出错！");
        return returnJson.getJSONObject("data");
    }

    public void delProcessInstances(String projectCode, String[] processInstanceIds) {
        JSONObject body = new JSONObject();
        body.set("processInstanceIds", String.join(",", processInstanceIds));
        send(HttpUtil.createPost(url("/projects/" + projectCode + "/process-instances/batch-delete"))
                .addHeaders(formHeader()).form(body), "删除工作流实例");
    }
}
