-- 风洞零线帐 SQLite schema
CREATE TABLE IF NOT EXISTS run_meta (
    id              INTEGER PRIMARY KEY,
    name            TEXT NOT NULL,
    drift_mode      TEXT NOT NULL DEFAULT 'LINEAR',   -- LINEAR | SEGMENT
    rotation_order  TEXT NOT NULL DEFAULT 'YZ',       -- YZ (先偏航 beta 后俯仰 alpha) | ZY (次序相反)
    calib_version   TEXT NOT NULL DEFAULT 'v1'
);

CREATE TABLE IF NOT EXISTS sample (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id       INTEGER NOT NULL REFERENCES run_meta(id),
    t_s          REAL NOT NULL,
    kind         TEXT NOT NULL,                       -- TARE（空载锨点）| TEST（测点）
    c1 REAL NOT NULL, c2 REAL NOT NULL, c3 REAL NOT NULL,
    c4 REAL NOT NULL, c5 REAL NOT NULL, c6 REAL NOT NULL,
    alpha_deg    REAL NOT NULL DEFAULT 0,
    beta_deg     REAL NOT NULL DEFAULT 0,
    q_pa         REAL NOT NULL DEFAULT 0,
    temp_k       REAL NOT NULL DEFAULT 0,
    tare_excluded INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_sample_run ON sample(run_id, t_s);

CREATE TABLE IF NOT EXISTS calib_version (
    code      TEXT PRIMARY KEY,
    label     TEXT NOT NULL,
    k_json        TEXT NOT NULL,   -- 6x6 天平标定（通道->天平坐标力/力矩）
    mount_json    TEXT NOT NULL,   -- 3x3 天平坐标->模型坐标安装矩阵
    rref_json     TEXT NOT NULL,   -- 模型力矩参考中心相对天平中心位置 (m)
    area_m2       REAL NOT NULL,
    span_m        REAL NOT NULL,
    chord_m       REAL NOT NULL
);

CREATE TABLE IF NOT EXISTS audit_log (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    at_iso     TEXT NOT NULL,
    action     TEXT NOT NULL,
    detail     TEXT NOT NULL
);
