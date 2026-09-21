package com.windtunnel.zeroledger.web;

import com.windtunnel.zeroledger.domain.Json;
import com.windtunnel.zeroledger.domain.RunSettings;
import com.windtunnel.zeroledger.domain.Sample;
import com.windtunnel.zeroledger.engine.AnalysisEngine;
import com.windtunnel.zeroledger.engine.Calibration;
import com.windtunnel.zeroledger.store.ImportExportService;
import com.windtunnel.zeroledger.store.WindTunnelRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final WindTunnelRepository repo;
    private final ImportExportService io;

    public ApiController(WindTunnelRepository repo, ImportExportService io) {
        this.repo = repo;
        this.io = io;
    }

    /** 总状态：run 列表 + 标定版本；页面首次加载使用。 */
    @GetMapping("/state")
    public String state() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runs", repo.listRuns().stream().map(r -> {
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("runId", r.runId());
            mm.put("name", r.name());
            mm.put("driftMode", r.driftMode());
            mm.put("rotationOrder", r.rotationOrder());
            mm.put("calibVersion", r.calibVersion());
            mm.put("sampleCount", repo.samplesOfRun(r.runId()).size());
            return mm;
        }).toList());
        m.put("calibVersions", repo.listCalibrations().stream().map(c -> {
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("code", c.code());
            mm.put("label", c.label());
            mm.put("areaM2", c.areaM2());
            mm.put("spanM", c.spanM());
            mm.put("chordM", c.chordM());
            return mm;
        }).toList());
        return Json.write(m);
    }

    /** run 的完整分析结果（可带临时覆盖参数；不落库）。 */
    @GetMapping("/runs/{id}/analysis")
    public String analysis(@PathVariable long id,
                           @RequestParam(required = false) String driftMode,
                           @RequestParam(required = false) String rotationOrder,
                           @RequestParam(required = false) String calibVersion) {
        RunSettings stored = repo.getRun(id);
        if (stored == null) {
            throw new NotFoundException("run 不存在: " + id);
        }
        RunSettings eff = new RunSettings(stored.runId(), stored.name(),
                normalize(driftMode, stored.driftMode(), "LINEAR", "SEGMENT"),
                normalize(rotationOrder, stored.rotationOrder(), "YZ", "ZY"),
                calibVersion == null ? stored.calibVersion() : calibVersion);
        Calibration cal = repo.getCalibration(eff.calibVersion());
        if (cal == null) {
            throw new NotFoundException("标定版本不存在: " + eff.calibVersion());
        }
        List<Sample> samples = repo.samplesOfRun(id);
        return Json.write(AnalysisEngine.analyzeRun(eff, samples, cal));
    }

    /** 持久化切换：漂移模式、旋转次序、标定版本。 */
    @PutMapping("/runs/{id}/settings")
    public String updateSettings(@PathVariable long id, @RequestBody String body) {
        Map<String, Object> req = Json.parseObject(body);
        RunSettings cur = repo.getRun(id);
        if (cur == null) {
            throw new NotFoundException("run 不存在: " + id);
        }
        String drift = normalize(str(req.get("driftMode")), cur.driftMode(), "LINEAR", "SEGMENT");
        String order = normalize(str(req.get("rotationOrder")), cur.rotationOrder(), "YZ", "ZY");
        String calib = str(req.getOrDefault("calibVersion", cur.calibVersion()));
        if (repo.getCalibration(calib) == null) {
            throw new NotFoundException("标定版本不存在: " + calib);
        }
        repo.updateRunSettings(id, drift, order, calib);
        repo.log("SETTINGS", "run " + id + " -> drift=" + drift + " order=" + order + " calib=" + calib);
        return Json.write(Map.of("ok", true));
    }

    /** 拒绝/恢复某个空载锨点参与拟合。 */
    @PutMapping("/samples/{sampleId}/tare-excluded")
    public String setTareExcluded(@PathVariable long sampleId, @RequestBody String body) {
        boolean excluded = Boolean.TRUE.equals(Json.parseObject(body).get("excluded"));
        repo.setTareExcluded(sampleId, excluded);
        repo.log(excluded ? "TARE_REJECT" : "TARE_ACCEPT",
                "空载锨点 sample " + sampleId + (excluded ? " 被拒绝" : " 恢复参与"));
        return Json.write(Map.of("ok", true));
    }

    @GetMapping("/audit")
    public String audit() {
        return Json.write(Map.of("auditLog", repo.auditLog()));
    }

    @GetMapping(value = "/export", produces = "application/json;charset=utf-8")
    public ResponseEntity<byte[]> exportData() {
        repo.log("EXPORT", "导出运行记录快照");
        byte[] body = io.exportSnapshot().getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"zeroledger-snapshot.json\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    @PostMapping("/import")
    public String importData(@RequestBody String body) {
        boolean clear = body.contains("\"clear\"") && Boolean.TRUE.equals(Json.parseObject(body).get("clear"));
        Map<String, Object> payload = Json.parseObject(body);
        Object snapshot = payload.get("snapshot");
        if (snapshot == null) {
            throw new IllegalArgumentException("缺少 snapshot 字段");
        }
        int n = io.importSnapshot(Json.write(snapshot), clear);
        repo.log("IMPORT", (clear ? "清空后" : "追加") + "导入快照，测点+锨点 " + n + " 条");
        return Json.write(Map.of("ok", true, "importedSamples", n));
    }

    @PostMapping("/reset")
    public String reset() {
        int n = io.importSnapshot(io.loadBundledFixture(), true);
        repo.log("RESET", "恢复内置 fixture（" + n + " 条记录）");
        return Json.write(Map.of("ok", true, "importedSamples", n));
    }

    @PostMapping("/clear")
    public String clear() {
        io.clearAll();
        return Json.write(Map.of("ok", true));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<String> notFound(NotFoundException e) {
        return ResponseEntity.status(404)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Json.write(Map.of("error", e.getMessage())));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(Json.write(Map.of("error", e.getMessage())));
    }

    private static String normalize(String value, String fallback, String a, String b) {
        if (value == null) {
            return fallback;
        }
        if (!value.equals(a) && !value.equals(b)) {
            throw new IllegalArgumentException("非法取值: " + value);
        }
        return value;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    static class NotFoundException extends RuntimeException {
        NotFoundException(String m) {
            super(m);
        }
    }
}
