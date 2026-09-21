# 风洞零线帐

本地 Spring Boot + SQLite + SVG 服务，用于在一次风洞 run 中交错查看空载锨点、六通道原值、漂移拟合、角度、动压与最终系数。

## 运行

```bash
mvn -q -DskipTests package
mvn -q test && mvn -q spring-boot:run -Dspring-boot.run.arguments=--server.port=5527
```

打开 <http://127.0.0.1:5527>，页面标题应为“风洞零线帐”。

默认 SQLite 文件为 `data/windtunnel.db`。停止服务后删除该文件再启动，会重新生成固定 fixture；也可以在页面导出 JSON，清空数据库后通过“导入运行记录”复核。

## 数据口径

- 六通道顺序固定为 `[Fx,Fy,Fz,Mx,My,Mz]`，前三个单位 N，后三个单位 N·m；原始表为采样计数。
- 模型坐标为右手系：x 指向前方，y 指向右翼，z 指向下方。
角度以弧度保存、页面显示度；α 为抬头为正，β 为机头向右翼为正。
- 空载漂移按通道分别拟合。线性模式对已接受空载锨点做最小二乘直线；分段模式在相邻锨点间线性插值，在最早或最晚锨点之外保持端点值并标记 `TARE_EXTRAPOLATED`。
- 拒绝空载锨点只会改变当前 run 的拟合；系统不会借用相邻 run 或下一个 run 的空载。
- 标定链为 `六通道校正值 -> G -> 天平坐标 -> B -> 模型坐标 -> 力矩平移 -> 风轴坐标 -> 系数`。
- 力矩平移严格使用叉积：`M_ref = M_model + r × F_model`，其中 `r` 是模型参考中心指向天平中心的向量。
- 可选旋转次序以“从模型到风轴的作用顺序”命名：`ALPHA_THEN_BETA_ZY` 为 `Rz(β)Ry(α)`，`BETA_THEN_ALPHA_YZ` 为 `Ry(α)Rz(β)`。
- 力系数除以 `qS`；滚转/偏航力矩除以 `qSb`，俯仰力矩除以 `qSc`。`q <= 0` 时系数和贡献为 `null`，不会产生 Infinity；原始力、平移力和诊断仍保留。

## 固定 fixture

`RUN-20260921-A` 包含试验前空载、试验点、零动压点、试验后空载和晚于空载包络的试验点。默认标定版本为 `v2024-车间复核`，可切换到 `v2023-初装标定` 查看矩阵链差异。

## HTTP 接口

- `GET /api/state`：当前 run、配置、样本、标定和分析结果。
- `PATCH /api/samples/{id}`：接受或拒绝空载锨点。
- `POST /api/runs/{runId}/config`：切换漂移模式、旋转次序或标定版本。
- `GET /api/export`：导出完整运行记录。
- `POST /api/import`：清空并重导入 JSON 运行记录。
- `POST /api/admin/reset-fixture`：恢复内置固定 fixture。
