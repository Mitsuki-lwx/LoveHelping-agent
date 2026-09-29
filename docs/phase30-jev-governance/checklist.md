# phase30 · checklist（JEV 治理）

> 判据见 `tasks.md`（实现前写死）。DoD = 编译 + 单测 + 触达面 E2E。

## A. 实现

- [x] A1 三件套（tasks / checklist；spec 并入 ADR-63，不另开文件）
- [x] A2 `JevProperties` 加 `maxConcurrent`(8) / `failureThreshold`(3) / `circuitOpenMs`(30000)
      —— 字段名与默认值**照抄 `RerankProperties`**（已有范式）
- [x] A3 `JevClient`：`Semaphore(maxConcurrent)` + `ProviderCircuit(failureThreshold, circuitOpenMs)`
      （`ProviderCircuit` 是 LlmGateway 与 reranker 共用的**既有**组件）
- [x] A4 熔断打开 → **直接回退**（不发 HTTP、立即 `Optional.empty()`）
- [x] A5 计量：`jev.call{outcome}`（success / fail / http_* / circuit_open）
- [x] A6 ⛔ **只有一个 `@Autowired` 构造器**（两个公开构造器会让 Spring 拒绝启动 —— 仓里有前车之鉴）
- [x] A7 yml 三个键 + 注释（`JEV_MAX_CONCURRENT` / `JEV_FAILURE_THRESHOLD` / `JEV_CIRCUIT_OPEN_MS`）

⛔ **顺序不动**：闸门与熔断都在 `available()` **之后** —— 既有测试断言"未启用/无 key 时**零请求**"。

## B. 验证

| # | 判据 | 结果 |
|---|---|---|
| **J1** | 熔断 fail-fast，**用调用计数证明"没发请求"** | ✅ `circuitOpensAndStopsSendingRequests`：前 2 次真的发了（计数=2），打开后连调 4 次**计数不变** |
| — | 熔断窗口过后允许再试（半开） | ✅ `circuitAllowsRetryAfterWindow`：窗口内不发、窗口后 +1 |
| **J2** | 并发闸门 | ✅ 配置被读取（信号量为 JDK 原语，行为由 `Semaphore` 保证） |
| **J3** | 语义零变化 | ✅ 未启用 → **0 请求**；失败 → `Optional.empty()`；既有 **15** 个 JEV 测试全绿 |
| **J4** | 可观测 | ✅ `jev.call` span 的 `jev.outcome` 增 `circuit_open`（值，非新键 → 无需改白名单） |
| **J5** | 无回归 | 见下 |

- [x] B1 编译 `BUILD SUCCESS`
- [x] B2 相关单测 **37/37**（新增 4 + 既有 33）
- [x] B3 全量单测 **367/367**（基线 363 + 4）
- [x] B4 真实 E2E **22/22**（含 A4 JEV shadow 断言）

## ⛔ 未验证 / 已知限制

1. **并发闸门的行为只在单测层钉住**（信号量是 JDK 原语，没有构造真实并发去压它）。
2. **熔断阈值/窗口的取值没有实测依据**：`failureThreshold=3` / `circuitOpenMs=30s` 是**照抄 rerank**
   的同类取值 + 常识，**不是**按 JEV 的真实故障分布定的（无该数据）。
3. **计量只在单测/代码层**，未在真实 Prometheus 上核对 `jev_call_total` 的分布。
4. ⛔ **JEV 的"出域"性质未变**：它仍把**用户原话**送去第三方（`docs/07` §6.1 已记，
   且这是**既有**事实、不是本轮引入）；本轮只补了治理，**没有**动出域口径。
5. ⛔ **JEV 仍绕过 `LlmGateway`**：本轮补的是"自己那一套"（闸门/熔断/计量），
   与网关的**重试预算/自适应并发收敛**不是同一套 —— 若要完全统一，需要更大的架构决定。
