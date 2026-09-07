package io.github.yanjin24;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import io.github.yanjin24.dolphinscheduler.DolphinSchedulerTool;

import java.util.Map;

public class OffLine {
    private static final Logger log = LogManager.getLogger(OffLine.class);

    public static void execute(SyncConfig config, String projectCode) {
        DolphinSchedulerTool dolphinSchedulerTool =
                new DolphinSchedulerTool(config.getDpHttpUrl(), config.getDpToken());
        offLine(dolphinSchedulerTool, projectCode);
    }

    private static void offLine(DolphinSchedulerTool dolphinSchedulerTool, String projectCode) {
        Map<String, String> processList = dolphinSchedulerTool.queryList(projectCode);
        processList.forEach((code, name) -> {
            dolphinSchedulerTool.release(projectCode, code, "OFFLINE");
            log.info("{}已下线", name);
        });
    }
}
