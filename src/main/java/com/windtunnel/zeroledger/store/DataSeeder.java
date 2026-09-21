package com.windtunnel.zeroledger.store;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class DataSeeder {

    private final ImportExportService io;

    public DataSeeder(ImportExportService io) {
        this.io = io;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        io.seedIfEmpty();
    }
}
