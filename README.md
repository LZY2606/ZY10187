# 风洞零线帐（Wind-Tunnel Zero Ledger）

一次风洞 run 中，天平六通道、迎角/侧滑角、动压、温度与空载试验交错记录。
本服务把**空载锨点 → 漂移拟合 → 零线修正 → 天平解耦 → 模型坐标 → 力矩参考中心平移 → 风轴旋转 → 气动力系数**
整条链全部落库、可视化并保留中间向量与矩阵，工程师可以：

- 勾选/拒绝每个空载锨点，切换**线性最小二乘**或**分段插补**漂移；
- 选择天平/安装**标定版本**；
- 交换风轴**旋转次序**（YZ / ZY）查看可见差异；
- 点击任一系数，回查它的分子、分母、叉积分项与全部中间矩阵；
- 导出整库运行记录，清空数据库后重新导入复核。

## 技术栈与运行

Java 17 字节码（在 JDK 17/24/26 上均可）、Spring Boot 4.1、SQLite（`sqlite-jdbc` 内嵌，无需安装数据库）、
前端为原生 HTML/SVG/JavaScript，无外部网络依赖。

```bash
# 安装/打包（跳测试）
mvn -q -DskipTests package

# 演示：先跑自动化测试，再启动本地服务
mvn -q test && mvn -q spring-boot:run -Dspring-boot.run.arguments=--server.port=5527
```

浏览器访问 <http://127.0.0.1:5527>，页面标题为 **风洞零线帐**。

SQLite 文件默认在 `./data/zeroledger.db`，可用环境变量 `WT_HOME` 改目录；
首次启动若库为空会自动导入内置 fixture。也可以直接运行
`java -jar target/zero-ledger-1.0.0.jar --server.port=5527`。

## 数据口径（可执行约定）

### 1. 坐标与角度

- **模型坐标（右手系）**：`x` 指向机头，`y` 指向**左翼**，`z` 竖直向上。`x×y=z`。
- **迎角 α**：机头抬头为正，即绕 `+y` 按右手定则旋转；
- **侧滑角 β**：机头向 `+y`（左翼方向）偏转时为正，绕 `+z` 右手定则。
- 角度统一换算成**弧度**参与矩阵运算。

### 2. 旋转次序（两种可切换，默认 YZ）

主动旋转矩阵（作用于列向量）：

```
Ry(θ) = [ cosθ  0  sinθ ]      Rz(θ) = [ cosθ -sinθ  0 ]
        [  0    1   0   ]              [ sinθ  cosθ  0 ]
        [-sinθ  0  cosθ ]              [  0     0    1 ]
```

- `YZ`（默认）：`R_w = Ry(−α) · Rz(β)`，先偏航 β 再俯仰 α；
- `ZY`（对比）：`R_w = Rz(β) · Ry(−α)`，交换次序。

矩阵乘法不满足交换律；当 `α、β 同时非零` 时两种次序给出**可见不同**结果，
fixture 的 t=40s 测点（α=5°, β=2°）即用于此项验收，风轴力相差约 2.4 N。

### 3. 零线、标定与力矩平移

对每个测点，记六通道原值 `raw`：

```
drift = Fit(t)                 # 仅使用同一 run 内、未被拒绝的空载锨点
zeroed = raw − drift           # 零线修正
w[6]  = K · zeroed             # K：6×6 标定增益，通道→天平坐标 [Fx,Fy,Fz,Mx,My,Mz]
F_model = mount · w[0..2]      # mount：3×3 天平→模型安装矩阵
M_bal   = mount · w[3..5]      # 天平中心处力矩（已转到模型轴）
M_ref   = M_bal + rRef × F_model     # ★ 力矩平移必须用叉积
F_wind  = R_w · F_model
M_wind  = R_w · M_ref
```

叉积分量（逐项回查，杜绝左右手系混淆）：

```
Mx = ry·Fz − rz·Fy
My = rz·Fx − rx·Fz
Mz = rx·Fy − ry·Fx
```

`rRef` 是模型力矩参考中心相对天平中心的位置（米）。

### 4. 漂移模型与外推

- `LINEAR`：对每个通道，用全部有效锨点做最小二乘直线；包络外按直线**延伸**，但必须标 `extrapolated`。
- `SEGMENT`：相邻锨点之间线性插补；包络外**钳到最近锨点值**（常量保持），同样标 `extrapolated`。
- 拟合**只使用本 run** 的锨点；测点超出本 run 锨点时间包络时页面橙色标注，**绝不借用相邻 run** 的空载。
- 有效锨点少于 2 个时不扣漂移，通道保留原值并给出诊断。

### 5. 系数与动压

```
Cd = −Fx_w / (q·S)   Cy = Fy_w / (q·S)   CL = −Fz_w / (q·S)
Croll = Mx_w / (q·S·b)   Cm = My_w / (q·S·c̄)   Cn = Mz_w / (q·S·b)
```

参考量 `S=0.12 m², b=0.30 m, c̄=0.40 m`（随标定版本）。
**动压 `q ≤ 0` 时不生成任何系数，字段为 `null`，绝不产生 Infinity/NaN**；
原始六通道、力、力矩与诊断信息照常保留。序列化器遇到 NaN/Infinity 会直接抛错，作为第二道防线。

## 页面分区

1. **状态序列与空载曲线**：六通道漂移折线、锨点（方框）、测点（圆点，q≤0 为红色）、锨点包络阴影、
   外推延伸虚线；锨点可通过复选框拒绝/恢复。
2. **六通道原值 → 零线 → 解耦**：每测点浅色柱为原值、实色柱为零线修正后。
3. **角度与坐标约定**：模型三轴 SVG、α/β 正方向、旋转次序与叉积分量公式。
4. **最终系数表**：点击任一系数弹出完整贡献链（分子/分母、叉积分项表格、K/mount/Ry/Rz/Rw 矩阵、全部中间向量、诊断）。

工具栏可切换 run、漂移模型、旋转次序、标定版本并“保存为本 run 设置”；所有操作写入 `audit_log`。

## 固定 fixture

内置 `fixture/fixture.json`（同时复制到 `src/main/resources/fixture.json` 供程序读取），格式标识
`wind-tunnel-zero-ledger/1`，由独立 Python 脚本按 `raw = K⁻¹·w_target + drift` 精确反推生成：

- **Run 1**：t=0/100s 两次空载（线性漂移），测点 t=40/65/90s（q>0，α、β 组合，含显著叉积耦合），
  以及 **t=130s 包络外测点**（必须标外推）；
- **Run 2**：t=0/100s 空载、**t=30s 动压 q=0**（系数必须全 null）、t=55s 正常测点；
- 力矩设计含大叉积分量（如 t=40s：`rRef×F=(126, 24, 18) N·m`），便于暴露左右手系/符号错误；
- 两套标定：`v1` 出厂基线（mount=I），`v2` 现场复检（1.5° 安装修正、rRef 略移）。

## 导出 / 清空 / 重导复核

```bash
# 导出整库（run、样本、标定版本、设置、操作记录）
curl -OJ http://127.0.0.1:5527/api/export

# 清空
curl -X POST http://127.0.0.1:5527/api/clear

# 从快照重新导入（"clear":true 表示导入前清空），再做分析复核
curl -X POST http://127.0.0.1:5527/api/import -H 'Content-Type: application/json' \
  --data '{"clear":true,"snapshot": <快照内容>}'

# 一键恢复内置 fixture
curl -X POST http://127.0.0.1:5527/api/reset
```

页面工具栏提供相同能力（导出按钮、选择快照文件并勾选“导入前清空”、恢复 fixture）。
导出格式与内置 fixture 完全一致，因此“导出 → 清空 → 导入”是可复核的闭环。

## 自动化测试

`mvn test` 覆盖（均使用内存 SQLite，互不污染）：

- 漂移线性/分段扣减、外推标记与包络外钳位；
- 力矩平移叉积的逐分量符号与贡献表；
- 交换 YZ/ZY 旋转次序的可见差异（数值断言）；
- q=0 时系数为 null、序列化无 Infinity/NaN、原始量保留；
- 拒绝锨点后漂移失效的降级；
- 导出→清空→导入→重新分析的持久化闭环；
- Web 层：首页标题、系数与外推标记、设置/锨点 HTTP 往返、404。

## 主要目录

```
src/main/java/com/windtunnel/zeroledger
  domain/    Json 工具、RunSettings、Sample
  engine/    LinAlg、DriftModel、Calibration、AnalysisEngine（纯函数分析链）
  store/     SQLite 仓储、快照导入导出、首启 seeder
  web/       REST 控制器与页面入口
src/main/resources/static  index.html / app.css / app.js（SVG 页面）
fixture/fixture.json       固定 fixture 源文件
src/test/...               引擎、持久化、Web 自动化测试
```
