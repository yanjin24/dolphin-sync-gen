package io.github.yanjin24.entity;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class DPDataSource {
    String type;
    String name;
    String host;
    String port;
    String database;
    String userName;
    String password;
    String note;
}
