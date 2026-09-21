package com.windtunnel.zeroledger.store;

import com.windtunnel.zeroledger.domain.Json;
import com.windtunnel.zeroledger.domain.RunSettings;
import com.windtunnel.zeroledger.domain.Sample;
import com.windtunnel.zeroledger.engine.Calibration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

@org.springframework.stereotype.Repository
public class WindTunnelRepository {

    private final JdbcTemplate jdbc;

    public WindTunnelRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<RunSettings> listRuns() {
        return jdbc.query("SELECT id, name, drift_mode, rotation_order, calib_version FROM run_meta ORDER BY id",
                (rs, n) -> new RunSettings(rs.getLong("id"), rs.getString("name"),
                        rs.getString("drift_mode"), rs.getString("rotation_order"),
                        rs.getString("calib_version")));
    }

    public RunSettings getRun(long id) {
        List<RunSettings> list = jdbc.query(
                "SELECT id, name, drift_mode, rotation_order, calib_version FROM run_meta WHERE id=?",
                (rs, n) -> new RunSettings(rs.getLong("id"), rs.getString("name"),
                        rs.getString("drift_mode"), rs.getString("rotation_order"),
                        rs.getString("calib_version")), id);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<Sample> samplesOfRun(long runId) {
        return jdbc.query("""
                SELECT id, run_id, t_s, kind, c1,c2,c3,c4,c5,c6,
                       alpha_deg, beta_deg, q_pa, temp_k, tare_excluded
                FROM sample WHERE run_id=? ORDER BY t_s, id""",
                (rs, n) -> new Sample(
                        rs.getLong("id"), rs.getLong("run_id"), rs.getDouble("t_s"),
                        rs.getString("kind"),
                        new double[]{rs.getDouble("c1"), rs.getDouble("c2"), rs.getDouble("c3"),
                                rs.getDouble("c4"), rs.getDouble("c5"), rs.getDouble("c6")},
                        rs.getDouble("alpha_deg"), rs.getDouble("beta_deg"),
                        rs.getDouble("q_pa"), rs.getDouble("temp_k"),
                        rs.getInt("tare_excluded") == 1),
                runId);
    }

    public Sample getSample(long id) {
        List<Sample> list = jdbc.query("""
                SELECT id, run_id, t_s, kind, c1,c2,c3,c4,c5,c6,
                       alpha_deg, beta_deg, q_pa, temp_k, tare_excluded
                FROM sample WHERE id=?""",
                (rs, n) -> new Sample(
                        rs.getLong("id"), rs.getLong("run_id"), rs.getDouble("t_s"),
                        rs.getString("kind"),
                        new double[]{rs.getDouble("c1"), rs.getDouble("c2"), rs.getDouble("c3"),
                                rs.getDouble("c4"), rs.getDouble("c5"), rs.getDouble("c6")},
                        rs.getDouble("alpha_deg"), rs.getDouble("beta_deg"),
                        rs.getDouble("q_pa"), rs.getDouble("temp_k"),
                        rs.getInt("tare_excluded") == 1), id);
        return list.isEmpty() ? null : list.get(0);
    }

    public void updateRunSettings(long id, String driftMode, String rotationOrder, String calibVersion) {
        int n = jdbc.update("""
                UPDATE run_meta SET drift_mode=?, rotation_order=?, calib_version=? WHERE id=?""",
                driftMode, rotationOrder, calibVersion, id);
        if (n == 0) {
            throw new IllegalArgumentException("run 不存在: " + id);
        }
    }

    public void setTareExcluded(long sampleId, boolean excluded) {
        int n = jdbc.update("UPDATE sample SET tare_excluded=? WHERE id=? AND kind='TARE'",
                excluded ? 1 : 0, sampleId);
        if (n == 0) {
            throw new IllegalArgumentException("空载锨点不存在: " + sampleId);
        }
    }

    public List<Calibration> listCalibrations() {
        return jdbc.query("SELECT code,label,k_json,mount_json,rref_json,area_m2,span_m,chord_m "
                        + "FROM calib_version ORDER BY code",
                (rs, n) -> new Calibration(
                        rs.getString("code"), rs.getString("label"),
                        readMatrix(rs.getString("k_json")),
                        readMatrix(rs.getString("mount_json")),
                        readVector(rs.getString("rref_json")),
                        rs.getDouble("area_m2"), rs.getDouble("span_m"), rs.getDouble("chord_m")));
    }

    public Calibration getCalibration(String code) {
        List<Calibration> list = jdbc.query("""
                SELECT code,label,k_json,mount_json,rref_json,area_m2,span_m,chord_m
                FROM calib_version WHERE code=?""",
                (rs, n) -> new Calibration(
                        rs.getString("code"), rs.getString("label"),
                        readMatrix(rs.getString("k_json")),
                        readMatrix(rs.getString("mount_json")),
                        readVector(rs.getString("rref_json")),
                        rs.getDouble("area_m2"), rs.getDouble("span_m"), rs.getDouble("chord_m")),
                code);
        return list.isEmpty() ? null : list.get(0);
    }

    public void log(String action, String detail) {
        jdbc.update("INSERT INTO audit_log(at_iso, action, detail) VALUES (?,?,?)",
                java.time.Instant.now().toString(), action, detail);
    }

    public List<Map<String, Object>> auditLog() {
        return jdbc.query("SELECT id, at_iso, action, detail FROM audit_log ORDER BY id DESC LIMIT 200",
                (rs, n) -> Map.of(
                        "id", rs.getLong("id"),
                        "at", rs.getString("at_iso"),
                        "action", rs.getString("action"),
                        "detail", rs.getString("detail")));
    }

    public long countSamples() {
        Long c = jdbc.queryForObject("SELECT COUNT(*) FROM sample", Long.class);
        return c == null ? 0 : c;
    }

    @SuppressWarnings("unchecked")
    private static double[][] readMatrix(String json) {
        List<Object> rows = (List<Object>) Json.parse(json);
        double[][] m = new double[rows.size()][];
        for (int i = 0; i < rows.size(); i++) {
            List<Object> row = (List<Object>) rows.get(i);
            m[i] = new double[row.size()];
            for (int j = 0; j < row.size(); j++) {
                m[i][j] = ((Number) row.get(j)).doubleValue();
            }
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    private static double[] readVector(String json) {
        List<Object> row = (List<Object>) Json.parse(json);
        double[] v = new double[row.size()];
        for (int i = 0; i < row.size(); i++) {
            v[i] = ((Number) row.get(i)).doubleValue();
        }
        return v;
    }
}
