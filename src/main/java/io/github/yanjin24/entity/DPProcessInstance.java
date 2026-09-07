package io.github.yanjin24.entity;

import lombok.Data;

import java.io.Serializable;

@Data
public class DPProcessInstance implements Serializable {
    String id;
    String processDefinitionCode;
    String commandType;
    String duration;
    String host;
    String scheduleTime;
    String startTime;
    String restartTime;
    String state;
    int maxTryTimes;
    boolean processInstanceStop;
    String name;
    String endTime;
    int runTimes;
}
