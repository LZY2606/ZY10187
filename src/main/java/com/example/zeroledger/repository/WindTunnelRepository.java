package com.example.zeroledger.repository;

import com.example.zeroledger.domain.AnalysisConfig;
import com.example.zeroledger.domain.Calibration;
import com.example.zeroledger.domain.Run;
import com.example.zeroledger.domain.Sample;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class WindTunnelRepository {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public WindTunnelRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void createSchema() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS runs (
                  id TEXT PRIMARY KEY,
                  name TEXT NOT NULL,
                  reference_area REAL NOT NULL,
                  wing_span REAL NOT NULL,
                  mean_chord REAL NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS samples (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  run_id TEXT NOT NULL,
                  elapsed_seconds REAL NOT NULL,
                  kind TEXT NOT NULL,
                  raw_channels TEXT NOT NULL,
                  alpha_radians REAL NOT NULL,
                  beta_radians REAL NOT NULL,
                  dynamic_pressure REAL NOT NULL,
                  temperature_celsius REAL NOT NULL,
                  tare_accepted INTEGER NOT NULL,
                  note TEXT NOT NULL,
                  FOREIGN KEY(run_id) REFERENCES runs(id)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS calibrations (
                  id TEXT PRIMARY KEY,
                  label TEXT NOT NULL,
                  balance_matrix TEXT NOT NULL,
                  balance_to_model_rotation TEXT NOT NULL,
                  reference_to_balance TEXT NOT NULL,
                  rotation_order TEXT NOT NULL,
                  sort_order INTEGER NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS analysis_configs (
                  run_id TEXT PRIMARY KEY,
                  drift_mode TEXT NOT NULL,
                  rotation_order TEXT NOT NULL,
                  calibration_id TEXT NOT NULL,
                  FOREIGN KEY(run_id) REFERENCES runs(id),
                  FOREIGN KEY(calibration_id) REFERENCES calibrations(id)
                )
                """);
    }

    @Transactional
    public void replaceAll(List<Run> runs, List<Sample> samples, List<Calibration> calibrations,
                           List<AnalysisConfig> configs) {
        jdbcTemplate.update("DELETE FROM analysis_configs");
        jdbcTemplate.update("DELETE FROM samples");
        jdbcTemplate.update("DELETE FROM calibrations");
        jdbcTemplate.update("DELETE FROM runs");
        jdbcTemplate.update("DELETE FROM sqlite_sequence WHERE name = 'samples'");
        for (Run run : runs) {
            insertRun(run);
        }
        for (Calibration calibration : calibrations) {
            insertCalibration(calibration);
        }
        for (Sample sample : samples) {
            insertSample(sample);
        }
        for (AnalysisConfig config : configs) {
            insertConfig(config);
        }
    }

    public void insertRun(Run run) {
        jdbcTemplate.update("""
                INSERT INTO runs(id, name, reference_area, wing_span, mean_chord)
                VALUES (?,?,?,?,?)
                """, run.id(), run.name(), run.referenceArea(), run.wingSpan(), run.meanChord());
    }

    public void insertCalibration(Calibration calibration) {
        jdbcTemplate.update("INSERT INTO calibrations(id, label, balance_matrix, "
                        + "balance_to_model_rotation, reference_to_balance, rotation_order, sort_order) "
                        + "VALUES (?,?,?,?,?,?,?)", calibration.id(), calibration.label(),
                write(calibration.balanceMatrix()), write(calibration.balanceToModelRotation()),
                write(calibration.referenceToBalance()), calibration.rotationOrder(),
                calibration.sortOrder());
    }

    public void insertSample(Sample sample) {
        jdbcTemplate.update("""
                INSERT INTO samples(run_id, elapsed_seconds, kind, raw_channels, alpha_radians,
                                    beta_radians, dynamic_pressure, temperature_celsius,
                                    tare_accepted, note)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, sample.runId(), sample.elapsedSeconds(), sample.kind(), write(sample.rawChannels()),
                sample.alphaRadians(), sample.betaRadians(), sample.dynamicPressure(),
                sample.temperatureCelsius(), sample.tareAccepted() ? 1 : 0, sample.note());
    }

    public void insertConfig(AnalysisConfig config) {
        jdbcTemplate.update("""
                INSERT INTO analysis_configs(run_id, drift_mode, rotation_order, calibration_id)
                VALUES (?,?,?,?)
                """, config.runId(), config.driftMode(), config.rotationOrder(), config.calibrationId());
    }

    public List<Run> findRuns() {
        return jdbcTemplate.query("SELECT * FROM runs ORDER BY id", runMapper());
    }

    public Optional<Run> findRun(String runId) {
        return jdbcTemplate.query("SELECT * FROM runs WHERE id = ?", runMapper(), runId).stream().findFirst();
    }

    public List<Sample> findSamples(String runId) {
        return jdbcTemplate.query("SELECT * FROM samples WHERE run_id = ? ORDER BY elapsed_seconds, id",
                sampleMapper(), runId);
    }

    public Optional<Sample> findSample(long sampleId) {
        return jdbcTemplate.query("SELECT * FROM samples WHERE id = ?", sampleMapper(), sampleId).stream()
                .findFirst();
    }

    public List<Calibration> findCalibrations() {
        return jdbcTemplate.query("SELECT * FROM calibrations ORDER BY sort_order, id", calibrationMapper());
    }

    public Optional<Calibration> findCalibration(String calibrationId) {
        return jdbcTemplate.query("SELECT * FROM calibrations WHERE id = ?", calibrationMapper(),
                calibrationId).stream().findFirst();
    }

    public Optional<AnalysisConfig> findConfig(String runId) {
        return jdbcTemplate.query("SELECT * FROM analysis_configs WHERE run_id = ?", configMapper(), runId)
                .stream().findFirst();
    }

    public void setTareAccepted(long sampleId, boolean accepted) {
        jdbcTemplate.update("UPDATE samples SET tare_accepted = ? WHERE id = ? AND kind = 'TARE'",
                accepted ? 1 : 0, sampleId);
    }

    public void saveConfig(AnalysisConfig config) {
        jdbcTemplate.update("""
                INSERT INTO analysis_configs(run_id, drift_mode, rotation_order, calibration_id)
                VALUES (?,?,?,?)
                ON CONFLICT(run_id) DO UPDATE SET
                  drift_mode = excluded.drift_mode,
                  rotation_order = excluded.rotation_order,
                  calibration_id = excluded.calibration_id
                """, config.runId(), config.driftMode(), config.rotationOrder(), config.calibrationId());
    }

    public long countRuns() {
        Long value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM runs", Long.class);
        return value == null ? 0 : value;
    }

    private RowMapper<Run> runMapper() {
        return (rs, rowNum) -> new Run(rs.getString("id"), rs.getString("name"),
                rs.getDouble("reference_area"), rs.getDouble("wing_span"), rs.getDouble("mean_chord"));
    }

    private RowMapper<Sample> sampleMapper() {
        return (rs, rowNum) -> new Sample(rs.getLong("id"), rs.getString("run_id"),
                rs.getDouble("elapsed_seconds"), rs.getString("kind"),
                read(rs.getString("raw_channels"), new TypeReference<>() {
                }), rs.getDouble("alpha_radians"), rs.getDouble("beta_radians"),
                rs.getDouble("dynamic_pressure"), rs.getDouble("temperature_celsius"),
                rs.getInt("tare_accepted") == 1, rs.getString("note"));
    }

    private RowMapper<Calibration> calibrationMapper() {
        return (rs, rowNum) -> new Calibration(rs.getString("id"), rs.getString("label"),
                read(rs.getString("balance_matrix"), new TypeReference<>() {
                }), read(rs.getString("balance_to_model_rotation"), new TypeReference<>() {
                }), read(rs.getString("reference_to_balance"), new TypeReference<>() {
                }), rs.getString("rotation_order"), rs.getInt("sort_order"));
    }

    private RowMapper<AnalysisConfig> configMapper() {
        return (rs, rowNum) -> new AnalysisConfig(rs.getString("run_id"), rs.getString("drift_mode"),
                rs.getString("rotation_order"), rs.getString("calibration_id"));
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("无法序列化数据库字段", exception);
        }
    }

    private <T> T read(String value, TypeReference<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (Exception exception) {
            throw new IllegalArgumentException("无法读取数据库字段", exception);
        }
    }
}
