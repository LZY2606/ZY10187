package com.windtunnel.zeroledger.store;

import com.windtunnel.zeroledger.domain.Json;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 快照导入/导出与首启播种。
 * 导出格式与 src/main/resources/fixture.json 完全一致，
 * 因此“导出 -> 清空 -> 导入 -> 重新分析”是可复核的闭环。
 */
@Service
public class ImportExportService {

    public static final String FORMAT = "wind-tunnel-zero-ledger/1";

    private final JdbcTemplate jdbc;
    private final WindTunnelRepository repo;

    public ImportExportService(JdbcTemplate jdbc, WindTunnelRepository repo) {
        this.jdbc = jdbc;
        this.repo = repo;
    }

    public String loadBundledFixture() {
        try {
            return new String(new ClassPathResource("fixture.json").getContentAsByteArray(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("内置 fixture 缺失", e);
        }
    }

    /** 数据库为空时导入内置 fixture。 */
    public void seedIfEmpty() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM run_meta", Long.class);
        if (n != null && n > 0) {
            return;
        }
        importSnapshot(loadBundledFixture(), false);
        repo.log("SEED", "首启自动导入内置 fixture");
    }

    @SuppressWarnings("unchecked")
    public synchronized int importSnapshot(String json, boolean clearFirst) {
        Map<String, Object> root = Json.parseObject(json);
        if (!FORMAT.equals(root.get("format"))) {
            throw new IllegalArgumentException("数据格式标识不符，期望 " + FORMAT);
        }
        List<Map<String, Object>> calibs = (List<Map<String, Object>>) root.get("calib_versions");
        List<Map<String, Object>> runs = (List<Map<String, Object>>) root.get("runs");
        if (calibs == null || runs == null || runs.isEmpty()) {
            throw new IllegalArgumentException("快照缺少 calib_versions 或 runs");
        }

        if (clearFirst) {
            jdbc.update("DELETE FROM sample");
            jdbc.update("DELETE FROM run_meta");
            jdbc.update("DELETE FROM calib_version");
            jdbc.update("DELETE FROM audit_log");
        }

        for (Map<String, Object> c : calibs) {
            Integer existing = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM calib_version WHERE code=?", Integer.class, str(c.get("code")));
            if (existing != null && existing > 0) {
                continue;
            }
            jdbc.update("INSERT INTO calib_version(code,label,k_json,mount_json,rref_json,"
                            + "area_m2,span_m,chord_m) VALUES (?,?,?,?,?,?,?,?)",
                    str(c.get("code")), str(c.get("label")),
                    Json.write(c.get("k")), Json.write(c.get("mount")), Json.write(c.get("rref")),
                    num(c.get("area_m2")), num(c.get("span_m")), num(c.get("chord_m")));
        }

        int sampleCount = 0;
        for (Map<String, Object> run : runs) {
            long runId = (long) num(run.get("id"));
            Map<String, Object> settings = (Map<String, Object>) run.get("settings");
            Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM run_meta WHERE id=?",
                    Integer.class, runId);
            if (exists == null || exists == 0) {
                jdbc.update("INSERT INTO run_meta(id,name,drift_mode,rotation_order,calib_version) "
                                + "VALUES (?,?,?,?,?)",
                        runId, str(run.get("name")),
                        str(settings.getOrDefault("drift_mode", "LINEAR")),
                        str(settings.getOrDefault("rotation_order", "YZ")),
                        str(settings.getOrDefault("calib_version", "v1")));
            }
            for (Map<String, Object> s : (List<Map<String, Object>>) run.get("samples")) {
                List<Object> ch = (List<Object>) s.get("channels");
                if (ch.size() != 6) {
                    throw new IllegalArgumentException("六通道字段长度必须为 6");
                }
                int excluded = asBool(s.get("tare_excluded")) ? 1 : 0;
                jdbc.update("""
                        INSERT INTO sample(run_id,t_s,kind,c1,c2,c3,c4,c5,c6,
                            alpha_deg,beta_deg,q_pa,temp_k,tare_excluded)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                        runId, num(s.get("t_s")), str(s.get("kind")),
                        num(ch.get(0)), num(ch.get(1)), num(ch.get(2)),
                        num(ch.get(3)), num(ch.get(4)), num(ch.get(5)),
                        num(s.getOrDefault("alpha_deg", 0)),
                        num(s.getOrDefault("beta_deg", 0)),
                        num(s.getOrDefault("q_pa", 0)),
                        num(s.getOrDefault("temp_k", 0)),
                        excluded);
                sampleCount++;
            }
        }
        return sampleCount;
    }

    @SuppressWarnings("unchecked")
    public synchronized String exportSnapshot() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", FORMAT);
        root.put("exported_at", java.time.Instant.now().toString());
        root.put("conventions", Map.of(
                "model_axes", "右手系：x 指向机头，y 指向左翼，z 竖直向上",
                "angle_positive", "alpha 抬头为正（风轴矩阵 Ry(-alpha)）；beta 机头向左为正 Rz(beta)",
                "rotation_order", "YZ=Ry(-alpha)·Rz(beta)（默认）；ZY=Rz(beta)·Ry(-alpha)",
                "moment_shift", "模型参考中心力矩 = 天平中心力矩 + rRef × F",
                "coefficients", "q<=0 不生成系数"));

        List<Map<String, Object>> calibs = jdbc.query(
                "SELECT code,label,k_json,mount_json,rref_json,area_m2,span_m,chord_m "
                        + "FROM calib_version ORDER BY code",
                (rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("code", rs.getString("code"));
                    m.put("label", rs.getString("label"));
                    m.put("k", Json.parse(rs.getString("k_json")));
                    m.put("mount", Json.parse(rs.getString("mount_json")));
                    m.put("rref", Json.parse(rs.getString("rref_json")));
                    m.put("area_m2", rs.getDouble("area_m2"));
                    m.put("span_m", rs.getDouble("span_m"));
                    m.put("chord_m", rs.getDouble("chord_m"));
                    return m;
                });
        root.put("calib_versions", calibs);

        List<Map<String, Object>> runs = new java.util.ArrayList<>();
        List<Long> runIds = jdbc.queryForList("SELECT id FROM run_meta ORDER BY id", Long.class);
        for (Long runId : runIds) {
            Map<String, Object> run = new LinkedHashMap<>();
            Map<String, Object> meta = jdbc.queryForMap(
                    "SELECT id,name,drift_mode,rotation_order,calib_version FROM run_meta WHERE id=?",
                    runId);
            run.put("id", ((Number) meta.get("id")).longValue());
            run.put("name", meta.get("name"));
            run.put("settings", Map.of(
                    "drift_mode", meta.get("drift_mode"),
                    "rotation_order", meta.get("rotation_order"),
                    "calib_version", meta.get("calib_version")));
            List<Map<String, Object>> samples = jdbc.query("""
                    SELECT t_s,kind,c1,c2,c3,c4,c5,c6,alpha_deg,beta_deg,q_pa,temp_k,tare_excluded
                    FROM sample WHERE run_id=? ORDER BY t_s,id""",
                    (rs, n) -> {
                        Map<String, Object> s = new LinkedHashMap<>();
                        s.put("t_s", rs.getDouble("t_s"));
                        s.put("kind", rs.getString("kind"));
                        s.put("channels", List.of(
                                rs.getDouble("c1"), rs.getDouble("c2"), rs.getDouble("c3"),
                                rs.getDouble("c4"), rs.getDouble("c5"), rs.getDouble("c6")));
                        s.put("alpha_deg", rs.getDouble("alpha_deg"));
                        s.put("beta_deg", rs.getDouble("beta_deg"));
                        s.put("q_pa", rs.getDouble("q_pa"));
                        s.put("temp_k", rs.getDouble("temp_k"));
                        if (rs.getInt("tare_excluded") == 1) {
                            s.put("tare_excluded", true);
                        }
                        return s;
                    }, runId);
            run.put("samples", samples);
            runs.add(run);
        }
        root.put("runs", runs);
        root.put("audit_log", repo.auditLog());
        return Json.writePretty(root);
    }

    public synchronized void clearAll() {
        jdbc.update("DELETE FROM sample");
        jdbc.update("DELETE FROM run_meta");
        jdbc.update("DELETE FROM calib_version");
        jdbc.update("DELETE FROM audit_log");
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static double num(Object o) {
        return o == null ? 0 : ((Number) o).doubleValue();
    }

    private static boolean asBool(Object o) {
        return Boolean.TRUE.equals(o);
    }
}
