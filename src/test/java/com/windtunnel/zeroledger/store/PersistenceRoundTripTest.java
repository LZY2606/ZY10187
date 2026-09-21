package com.windtunnel.zeroledger.store;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class PersistenceRoundTripTest {

    @Autowired
    ImportExportService io;
    @Autowired
    WindTunnelRepository repo;

    @Test
    void exportClearImportRecomputesIdentically() {
        // 首启 seeder 已载入 fixture
        assertNotNull(repo.getRun(1));
        assertNotNull(repo.getCalibration("v1"));
        assertNotNull(repo.getCalibration("v2"));
        assertEquals(6, repo.samplesOfRun(1).size());
        assertEquals(4, repo.samplesOfRun(2).size());

        String snapshot = io.exportSnapshot();
        assertTrue(snapshot.contains("wind-tunnel-zero-ledger/1"));
        assertTrue(snapshot.contains("RUN-2026-0921"));

        io.clearAll();
        assertNull(repo.getRun(1));
        assertEquals(0, repo.countSamples());

        int imported = io.importSnapshot(snapshot, true);
        assertEquals(10, imported);
        assertEquals(6, repo.samplesOfRun(1).size());
        assertEquals(4, repo.samplesOfRun(2).size());
        assertEquals("LINEAR", repo.getRun(1).driftMode());
        assertEquals("SEGMENT", repo.getRun(2).driftMode());
        assertNotNull(repo.getCalibration("v2"));
    }

    @Test
    void settingsAndTareRejectionPersistAndAudit() {
        repo.updateRunSettings(1, "SEGMENT", "ZY", "v2");
        assertEquals("ZY", repo.getRun(1).rotationOrder());
        assertEquals("v2", repo.getRun(1).calibVersion());

        long tareId = repo.samplesOfRun(1).stream()
                .filter(s -> "TARE".equals(s.kind()))
                .map(s -> s.id()).findFirst().orElseThrow();
        repo.setTareExcluded(tareId, true);
        assertTrue(repo.getSample(tareId).tareExcluded());
        repo.setTareExcluded(tareId, false);
        assertFalse(repo.getSample(tareId).tareExcluded());
        assertFalse(repo.auditLog().isEmpty());

        // 还原默认设置，保证与导出测试顺序无关
        repo.updateRunSettings(1, "LINEAR", "YZ", "v1");
    }

    @Test
    void rejectsUnknownFormat() {
        assertThrows(IllegalArgumentException.class,
                () -> io.importSnapshot("{\"format\":\"other\"}", false));
    }
}
