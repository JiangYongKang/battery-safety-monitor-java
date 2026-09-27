# 动力电池安全监控服务（本地可运行）

基于 Spring Boot 3（Java 21）的本地电池安全监控服务：以模拟遥测数据打通
**采样接入 → 状态评估 → 分级告警与处置** 全链路。不依赖任何外部服务、真实设备或真实凭据。

## 快速开始

```bash
# 构建并运行全部测试（28 个用例：正常 / 边界 / 并发）
mvn test

# 本地启动（内置模拟遥测源，默认开启）
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8080"

# 观察链路（另开终端）
curl -s localhost:8080/api/monitor/PACK-BRAVO/assessment   # 评估结论（含判定依据）
curl -s localhost:8080/api/monitor/alerts                  # 全部告警
curl -s localhost:8080/api/monitor/PACK-CHARLIE/audit      # 审计时间线
```

模拟器行为（`TelemetrySimulator`，可用 `simulator.enabled=false` 关闭）：

| 电池包 | 协议 | 行为 |
|---|---|---|
| PACK-ALPHA | V2 | 正常工况，温度小幅波动 |
| PACK-BRAVO | V1 | 缓慢升温，约 40s 后越过告警阈值，演示逐级升级 |
| PACK-CHARLIE | V2 | 第 25 个采样周期起绝缘失效，演示 CRITICAL + EMERGENCY_STOP |

## 遥测模型

### 接入协议（`POST /api/telemetry/batch`）

```json
{
  "batchId": "b-001",
  "packId": "PACK-1",
  "protocolVersion": "V2",
  "samples": [{
    "packId": "PACK-1",
    "sequenceNumber": 1,
    "timestampMillis": 1790491600000,
    "voltage": 400.0,
    "current": 50.0,
    "temperature": 35.0,
    "insulationOk": true
  }]
}
```

- 支持多电池包（按 `packId` 隔离状态）与多协议版本（`V1` / `V2`）。
- 数值字段为可空类型，**缺失**与**零值**可区分。
- 样本缺 `packId` 时以批次级 `packId` 兜底；`protocolVersion` 缺省按 V1 处理。

### 校验与拒绝原因（不静默丢弃）

每条样本独立裁决，拒绝原因可区分（`IngestResult.rejections`）：

| 拒绝原因 | 触发条件 |
|---|---|
| `MISSING_REQUIRED_FIELD` | packId / sequenceNumber / timestampMillis / voltage / current / temperature 缺失 |
| `PROTOCOL_REQUIRED_FIELD_MISSING` | V2 协议缺少 `insulationOk` |
| `VALUE_OUT_OF_RANGE` | 电压 [0,1000]V、电流 [-500,500]A、温度 [-40,125]°C 之外，或 NaN/非法序号时间戳 |
| `DUPLICATE_SAMPLE` | 同一 packId 下 sequenceNumber 重复上报 |

**重复与乱序**：重复序号被拒绝且不重复累计；乱序样本（新序号但时间戳不晚于窗口最新值）
被受理但拒绝进入滑动窗口，**不会使状态回退或覆盖较新结论**。接入按 packId 串行化，
并发上报结论一致（同批次并发投递只会被接受一次）。

## 状态机与阈值规则

### 滑动窗口

- 窗口跨度 30s、容量 120 条、最少 5 条（`MIN_WINDOW_SAMPLES`）才做统计升级判定 —— 窗口不足不误报。
- 统计升级要求窗口内超阈值样本占比 ≥ 50%（`BREACH_FRACTION`）—— 短暂波动/单点尖峰不误报。
- 热失控风险分基于**窗口均温**与**逐段温升速率中位数**（对尖峰稳健）：
  `risk = 0.6 × 均温因子 + 0.4 × 温升因子`，均温因子以 45°C→70°C 归一，温升因子以 2°C/s 归一。

### 健康状态（严重度单调：NORMAL < ATTENTION < WARNING < CRITICAL）

| 状态 | 触发条件（窗口 ≥5 条且占比 ≥50%，绝缘除外） |
|---|---|
| ATTENTION | 温度 ≥ 48°C，或 risk ≥ 0.3 |
| WARNING | 温度 ≥ 55°C，或电压越出 (300V, 450V)，或 risk ≥ 0.5 |
| CRITICAL | 温度 ≥ 70°C，或电压越出 (250V, 480V)，或（risk ≥ 0.8 且温升 ≥ 2°C/s），或**绝缘失效（单条即触发）** |

### 迁移规则（单调且可回溯）

- **升级即时**：候选状态更严重即迁移。
- **降级（恢复）条件明确**：窗口满（≥5 条）且窗内样本全部干净（温度 <48°C、电压在
  (300V,450V) 内、绝缘正常）才允许恢复，否则保持原状态。
- 乱序旧数据无法入窗，因此不会覆盖较新结论。
- 每次迁移写入审计日志（`STATE_TRANSITION`，含完整判定依据 basis），可回溯。

## 告警抑制策略与处置

| 状态 | 告警级别 | 处置动作 | 触发持续时间 |
|---|---|---|---|
| ATTENTION | INFO | NOTIFY | 持续 3s |
| WARNING | WARN | LIMIT_POWER | 持续 2s |
| CRITICAL | CRITICAL | EMERGENCY_STOP | 立即 |

- **抑制**：同一故障（`packId:状态级别`）在一次故障期内只产生一次外部告警，
  且 60s 抑制窗口内绝不重复；被抑制的触发也记入审计（`ALERT_SUPPRESSED`）。
- **重新触发条件明确**：状态先恢复 NORMAL（故障期关闭，记 `RECOVERY`），再次越限并满足
  持续时间后才允许新告警；未恢复时即使超过抑制窗口也不重复告警。
- **旧结论防护**：`assessedAtMillis` 不晚于已处理水位的评估结果被忽略。
- **并发安全**：按 packId 串行处理，并发/重复触发只产生一次告警与一次处置。
- **可审计**：`ALERT_TRIGGERED` / `ALERT_SUPPRESSED` / `DISPOSAL_EXECUTED` / `RECOVERY` /
  `STATE_TRANSITION` 全部带触发依据与时间戳，经 `GET /api/monitor/{packId}/audit` 查询。

## REST 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/telemetry/batch` | 批次上报遥测，返回逐条受理/拒绝结论 |
| GET | `/api/monitor/{packId}/assessment` | 当前评估结论（状态、健康度、风险分、判定依据） |
| GET | `/api/monitor/alerts` · `/api/monitor/{packId}/alerts` | 告警查询 |
| GET | `/api/monitor/{packId}/audit` | 审计时间线 |

## 本地验证方法

```bash
mvn test          # 全部 28 个用例
mvn test -Dtest=AssessmentServiceTest   # 单跑某类
```

测试覆盖（日志均打印关键输入与判定依据）：

- **SampleValidatorTest**：字段缺失 / 协议必填项 / 超量程 / NaN / 量程边界值。
- **TelemetryIngestionServiceTest**：拒绝原因可区分、重复上报去重、乱序不回退、
  8 线程并发投递同批次仅接受一次。
- **AssessmentServiceTest**：窗口不足不误报、单点尖峰不误报、阈值临界（恰好 55°C vs 54.9°C）、
  单调升级、绝缘失效即时升级、恢复需完整干净窗口、迁移可审计。
- **AlertServiceTest**：持续时间触发、抑制窗口内重复触发只告警一次、恢复后才可重触发、
  旧结论被忽略、16 线程并发评估只产生一次告警与处置、处置审计含触发依据。
- **EndToEndIntegrationTest**：REST 混合批次（有效+缺字段+超量程）→ 评估 → 告警 → 审计全链路；
  多入口上报结论一致；未知电池包 404。
