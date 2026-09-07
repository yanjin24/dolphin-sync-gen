package io.github.yanjin24;

import cn.hutool.core.util.StrUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Main {
    private static final Logger log = LogManager.getLogger(Main.class);

    public static void main(String[] args) {
        String basePath = System.getProperty("user.dir");
        String configPath = basePath + "/config.json";
        SyncConfig config = SyncConfig.load(configPath);
        if (args.length > 0) {
            if (args[0].equalsIgnoreCase("CreateTable")) {
                CreateTable.execute(config, true);
            } else if (args[0].equalsIgnoreCase("CreateTableNoKey")) {
                CreateTable.execute(config, false);
            } else if (args[0].equalsIgnoreCase("CreateHiveTable")) {
                CreateHiveTable.execute(config);
            } else if (args[0].equalsIgnoreCase("CreateHiveProcess")) {
                CreateHiveProcess.execute(config);
            } else if (StrUtil.isNumeric(args[0])) {
                OffLine.execute(config, args[0]);
            } else {
                log.error("参数无效: {}", args[0]);
            }
        } else {
            CreateProcess.execute(config);
        }
    }
}
