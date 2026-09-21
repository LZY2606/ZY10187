package com.windtunnel.zeroledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ZeroLedgerApplication {

    public static void main(String[] args) {
        // 保证默认 SQLite 目录存在（可用 -DWT_HOME 或环境变量覆盖）
        String home = System.getProperty("WT_HOME", System.getenv().getOrDefault("WT_HOME", "."));
        new java.io.File(home, "data").mkdirs();
        if (System.getProperty("WT_HOME") == null) {
            System.setProperty("WT_HOME", home);
        }
        SpringApplication.run(ZeroLedgerApplication.class, args);
    }
}
