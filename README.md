# 动力电池安全监控服务（本地可运行）

一套基于 Spring Boot 3 / Java 21 的**纯本地、零外部依赖**动力电池安全监控服务。
使用模拟遥测数据打通三条链路：

1. **采样接入**：多电池包、多协议版本、按批次上报电压/电流/温度/绝缘；
2. **状态评估**：基于连续采样窗口的健康度评分与热失控风险评估，状态迁移单调可回溯；
3. **故障处置**：分级告警 + 分级处置动作，抑制窗口去重，恢复后可重新触发，全程可审计。

不依赖任何外部服务、真实设备或真实凭据；所有数据保存在内存中，重启即清空。

---

## 1. 快速开始

```bash
# 运行全部测试（23 个用例，覆盖正常/边界/并发）
mvn test

# 启动服务（默认端口 8080，不开启内置模拟器）
mvn spring-boot:run

# 启动服务并开启内置确定性模拟遥测（推荐的本地演示方式）
mvn spring-boot:bootRun -Dspring-boot.run.arguments=--battery.simulator.enabled=true
# 或打包后运行
mvn package -DskipTests
java -jar target/battery-safety-monitor-java-1.0-SNAPSHOT.jar --battery.simulator.enabled=true
```

模拟器剧本（确定性、无随机，结论可复现）：

| 电池包 | 协议 | 剧本 |
|--------|------|------|
| `PACK-A` | v2（含绝缘） | 温度以 ~1.3℃/s 持续爬升，依次触发 `TEMP_WARN → TEMP_CRIT → THERMAL_RUNAWAY` |
| `PACK-B` | v1（无绝缘字段） | 始终正常，状态保持 `NORMAL` |

所有时间戳使用模拟时钟（固定起点按步长推进），不依赖墙钟，结论完全确定。

---

## 2. HTTP 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/telemetry` | 上报一个采样批次（接入 + 联动评估 + 联动处置） |
| POST | `/api/evaluate` | 对所有已知电池包手动触发一次评估（用于时间推进后的恢复判定） |
| GET  | `/api/packs` | 已知电池包列表 |
| GET  | `/api/packs/{packId}` | 电池包状态快照（状态、健康分、活动故障、状态时间线） |
| GET  | `/api/packs/{packId}/alarms` | 外部告警历史（含触发依据） |
| GET  | `/api/packs/{packId}/actions` | 处置动作审计历史 |

### 上报报文示例

```json
{
  "batchId": "B-20260927-0001",
  "packId": "PACK-A",
  "protocolVersion": "v2",
  "sentAtMs": 1700000012000,
  "samples": [
    {"seq": 0, "timestampMs": 1700000000000, "voltageV": 3.7, "currentA": 50.0, "tempC": 46.2, "insulationKOhm": 800.0}
  ]
}
```

字段说明见下节。畸形 JSON 返回 `reason=MALFORMED_PAYLOAD`，不会被静默吞掉。

### curl 示例

```bash
curl -s -X POST localhost:8080/api/telemetry -H 'Content-Type: application/json' -d '{
  "batchId":"B1","packId":"PACK-A","protocolVersion":"v2","sentAtMs":1700000012000,
  "samples":[
    {"seq":0,"timestampMs":1700000000000,"voltageV":3.7,"currentA":50.0,"tempC":50.0,"insulationKOhm":800.0},
    {"seq":1,"timestampMs":1700000003000,"voltageV":3.7,"currentA":50.0,"tempC":50.0,"insulationKOhm":800.0},
    {"seq":2,"timestampMs":1700000006000,"voltageV":3.7,"currentA":50.0,"tempC":50.0,"insulationKOhm":800.0},
    {"seq":3,"timestampMs":1700000009000,"voltageV":3.7,"currentA":50.0,"tempC":50.0,"insulationKOhm":800.0},
    {"seq":4,"timestampMs":1700000012000,"voltageV":3.7,"currentA":50.0,"tempC":50.0,"insulationKOhm":800.0}
  ]}'
curl -s localhost:8080/api/packs/PACK-A
curl -s localhost:8080/api/packs/PACK-A/alarms
curl -s localhost:8080/api/packs/PACK-A/actions
```

---

## 3. 遥测模型

```
TelemetryBatch（批次）
├── batchId           批次唯一标识，重复上报幂等的 key
├── packId            电池包标识
├── protocolVersion   协议版本：v1 / v2
├── sentAtMs          批次发送时间戳（模拟时钟，乱序判定基准）
└── samples[]         TelemetrySample
      ├── seq             批次内序号（不可重复）
      ├── timestampMs     采样时间戳（严格递增，不可重复、不可回退）
      ├── voltageV        电压 V（必填）
      ├── currentA        电流 A（必填，正负均可）
      ├── tempC           温度 ℃（必填）
      └── insulationKOhm  绝缘电阻 kΩ（v2 必填，v1 不上报）
```

### 协议差异

| 协议 | `insulationKOhm` |
|------|------------------|
| v1 | 无此字段，绝缘类故障不参与判定 |
| v2 | **必填**，缺失即拒绝（`MISSING_REQUIRED_FIELD`） |

### 量程（`battery.monitor.range.*`，超出即拒绝，原因 `OUT_OF_RANGE`，NaN/Infinity 同样拒绝）

| 量 | 下限 | 上限 |
|----|------|------|
| 电压 | 2.0 V | 5.0 V |
| 电流 | -500 A | 500 A |
| 温度 | -40 ℃ | 85 ℃ |
| 绝缘 | 0 kΩ | 100000 kΩ |

### 拒绝原因（`RejectReason`，全部可区分，绝不静默丢弃）

| 原因 | 层级 | 含义 |
|------|------|------|
| `MALFORMED_PAYLOAD` | 入口/批次 | 请求体不是合法 JSON 或为空 |
| `MISSING_REQUIRED_FIELD` | 批次/采样 | 协议必填项缺失（含 v2 缺绝缘） |
| `PROTOCOL_VERSION_UNSUPPORTED` | 批次 | 协议版本不在 `v1,v2` 中 |
| `EMPTY_BATCH` | 批次 | 批次没有任何采样 |
| `OUT_OF_RANGE` | 采样 | 字段超量程或为 NaN/Infinity |
| `DUPLICATE_BATCH` | 批次 | 同一 `batchId` 重复上报（只生效一次） |
| `STALE_BATCH` | 批次 | `sentAtMs` 早于已提交游标（后到的旧批次） |
| `DUPLICATE_SAMPLE` | 采样 | 批次内 `seq` 重复或 `timestampMs` 重复 |
| `STALE_SAMPLE` | 采样 | 采样时间戳不大于包内最新已接收时间戳 |

批次内允许"部分采样合法、部分非法"：合法采样照常接收，非法采样逐条给出拒绝原因；
当且仅当全部采样非法时，整批不生效（不登记 `batchId`、不推进游标）。

### 乱序与并发的确定语义

- 批次按 `sentAtMs` **单调提交**。晚于已提交游标的批次才生效；更早到达的批次判 `STALE_BATCH`，
  因此乱序到达**不会重复累计，也不会让旧数据回退较新结论**。
- 同一 `batchId` 在并发下只有一个线程提交成功，其余全部得到 `DUPLICATE_BATCH`（已验证 16 线程并发）。
- 每个并发批次都有确定结论（生效或明确拒绝原因），不存在静默丢失。
- 实现上每个电池包一把独立锁（`PackRegistry#lockFor`），接入、评估、告警、处置在同一把锁内完成；
  不同电池包互不阻塞。

---

## 4. 状态机与阈值规则

### 4.1 健康状态机（严重度单调）

```
NORMAL(0) ──► WARNING(1) ──► CRITICAL(2) ──► THERMAL_RUNAWAY(3)
   ▲              ▲                │
   └──────────────┴────────────────┘
   仅在"故障条件持续消失 recoveryHoldMs"后才允许降级（带迟滞，防抖动）
```

- **升级立即生效**（允许跳级，例如候选集同时含 CRITICAL 故障与热失控时直接进入 `THERMAL_RUNAWAY`）；
- **降级必须迟滞**：故障从滑动窗口消失并持续 `recovery-hold-ms` 无违例后才清除并降级；
- 每次迁移写入状态时间线 `StateTransition{timestampMs, from, to, reason}`，可通过
  `GET /api/packs/{packId}` 查询，全程可回溯。

### 4.2 连续采样窗口

- 滑动窗口长度 `window.size-ms = 60000ms`（另设 1000 条硬上限保护内存）；
- 故障成立必须同时满足：
  1. **连续违例后缀样本数 ≥ `window.min-samples = 3`**；
  2. **连续违例持续时间 ≥ 该等级的 hold**（见下）。
- 任一中间采样不再违例，连续后缀计数清零 —— 短暂波动不触发；窗口不足不触发。
- 恢复保持的计时基准是"**距窗口内最后一次实际违例的时间**"，而非本次评估时刻，
  因此结论与上报频率无关（稀疏上报也能在旧违例滑出窗口后正确恢复）。

### 4.3 故障阈值（默认值，均可在 `application.properties` 调整）

临界值采用**闭区间**判定：高温/高压/过流用 `>=`，低温/低压/绝缘偏低用 `<=`。

| 故障 key | 条件 | 等级 | 持续时间门槛 |
|----------|------|------|--------------|
| `TEMP_WARN` | `tempC >= 45.0℃` | WARN | 10s |
| `TEMP_CRIT` | `tempC >= 60.0℃` | CRITICAL | 3s |
| `OVERVOLTAGE_WARN` | `voltageV >= 4.20V` | WARN | 10s |
| `OVERVOLTAGE_CRIT` | `voltageV >= 4.30V` | CRITICAL | 3s |
| `UNDERVOLTAGE_WARN` | `voltageV <= 3.20V` | WARN | 10s |
| `UNDERVOLTAGE_CRIT` | `voltageV <= 2.80V` | CRITICAL | 3s |
| `OVERCURRENT_WARN` | `|currentA| >= 200A` | WARN | 10s |
| `OVERCURRENT_CRIT` | `|currentA| >= 300A` | CRITICAL | 3s |
| `INSULATION_WARN` | `insulationKOhm <= 500kΩ`（仅 v2） | WARN | 10s |
| `INSULATION_CRIT` | `insulationKOhm <= 100kΩ`（仅 v2） | CRITICAL | 3s |
| `THERMAL_RUNAWAY` | 最新温度 ≥ 50℃ **且** 窗口温升速率 ≥ 1.0℃/s | CRITICAL | 3s（并需 ≥3 样本） |

> 热失控的温升速率 = `(最新温度 − 窗口最早温度) × 1000 / (时间戳跨度 ms)`；
> 时间跨度为 0 或样本不足时不构成违例。

### 4.4 健康度评分

从 100 分起扣，按活动故障叠加，下限 0：

- 每个活动 WARN 故障：−8
- 每个活动 CRITICAL 故障：−20
- `THERMAL_RUNAWAY`：−40

---

## 5. 告警分级、处置动作与抑制策略

### 5.1 分级与动作（全部本地模拟，无外呼）

| 等级 | 处置动作 |
|------|----------|
| INFO | `LOG_ALERT`（本地日志告警） |
| WARN | `LOG_ALERT` + `DASHBOARD_FLAG`（模拟看板置标） |
| CRITICAL | 上述两项 + `RELAY_CUTOFF`（模拟切断继电器） |

每次动作生成一条 `ActionResult`（唯一 actionId、时间戳、触发依据、上下文），
可通过 `GET /api/packs/{packId}/actions` 审计。

### 5.2 告警抑制与重新触发

对每个 **电池包 + 故障类型** 独立维护抑制状态：

1. 故障**首次成立**：立即产生一次外部告警；
2. 故障持续期间重复评估/重复触发：距上次外部告警不足 `suppression.window-ms = 60000ms` 时，
   **只抑制外部告警**（处置动作仍留痕），即同一故障在抑制窗口内只有一次外部告警；
3. 超过抑制窗口且故障仍活动：允许再产生一次周期性提醒告警；
4. **等级升级**（WARN→CRITICAL）视为更严重的新结论，立即产生 CRITICAL 告警；
5. **重新触发条件（明确）**：故障必须先被评估确认恢复（条件持续消失满 `recoveryHoldMs`），
   抑制状态才重置；之后同类故障再次成立时才会产生新的外部告警。

### 5.3 可解释性

每条告警与处置都携带 `triggerEvidence`，例如：

```
温度偏高 {V=3.7V, I=50.0A, T=50.0℃, R=800.0kΩ}; 连续违例样本=5, 持续=12000ms, 触发要求=10000ms, ts=...
```

其中给出了判定时刻的关键输入量、连续违例样本数、实际持续时间与门槛，便于审计与复盘。

---

## 6. 并发与一致性保证

| 风险 | 保证机制 |
|------|----------|
| 同一批次并发重复上报 | `processedBatches` 幂等表 + 包级锁，仅一次提交，其余 `DUPLICATE_BATCH` |
| 乱序/后到旧数据覆盖新结论 | `sentAtMs` 单调提交 + 采样时间戳游标，旧批次/旧采样 `STALE_*` 拒绝 |
| 并发评估重复处置 | 评估在接入锁内同步执行；告警按 包+故障 抑制，动作生成走串行自增 ID |
| 状态回退 | 升级即时、降级迟滞；状态时间线仅追加 |
| 多包互相阻塞 | 每包一把锁 |
| 内存无限增长 | 幂等表 2048 条、时间线/告警/动作历史各有界（默认 200/500），窗口 60s+1000 条 |

---

## 7. 测试

`src/test/java/.../tests` 下共 **23 个用例**，日志中会打印关键输入与判定依据（`[用例]` 前缀）：

| 测试类 | 覆盖场景 |
|--------|----------|
| `IngestionRejectionTest` | 批次/采样字段缺失、v2 绝缘必填、逐字段超量程、空批次、协议不支持、重复批次、乱序迟到批次、seq/时间戳重复、旧采样 |
| `AssessmentStateMachineTest` | 正常基线、短暂波动与窗口不足防误报、阈值临界（45.0 触发 / 44.9 不触发）、WARN 持续时间、CRITICAL、热失控温升速率、逐级单调时间线、恢复迟滞 |
| `AlarmSuppressionTest` | WARN 动作集、CRITICAL 切断继电器、抑制窗口内重复触发仅一次告警、恢复后重新触发 |
| `ConcurrencyConsistencyTest` | 16 线程同批次仅一次生效、12 批次乱序并发结论确定无静默丢失、8 线程并发触发仅一次告警/一次切断 |
| `TelemetryApiTest` | HTTP 正常上报、重复上报幂等、畸形 JSON 归类为 `MALFORMED_PAYLOAD`、状态/告警/动作查询、未知包 404 |

```bash
mvn test                      # 全量
mvn test -Dtest=AlarmSuppressionTest   # 单个测试类
```

---

## 8. 配置项一览（`application.properties`）

所有规则均可通过配置覆盖，无需改代码：

```properties
battery.monitor.supported-protocols=v1,v2
battery.monitor.window.size-ms=60000
battery.monitor.window.min-samples=3
battery.monitor.thresholds.temp-warn-c=45.0
battery.monitor.thresholds.temp-crit-c=60.0
# ……其余阈值见 application.properties
battery.monitor.duration.warn-hold-ms=10000
battery.monitor.duration.crit-hold-ms=3000
battery.monitor.duration.recovery-hold-ms=15000
battery.monitor.suppression.window-ms=60000
battery.simulator.enabled=false
```

## 9. 代码结构

```
model/       领域模型（记录/枚举）：遥测、拒绝原因、状态、故障、告警、动作、快照
config/      MonitorProperties（规则配置）、SimulatorProperties（模拟器开关）
service/     PackRegistry（运行态+包级锁）、IngestService（接入校验）、
             AssessmentService（窗口评估/状态机）、AlarmService（告警抑制/处置审计）
simulator/   确定性本地模拟遥测数据源（默认关闭）
web/         REST 控制器与全局异常归类
```
