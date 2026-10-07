# Versioned API Gateway（Java / Spring Boot）

对外 API 集成网关：同一入口 `/api/orders` 支持多版本共存，按调用方做并发限流与配额控制，
对本地模拟上游的超时/错误/畸形响应做归一化，并在上游连续抖动时按依赖维度熔断、
对可降级请求返回可分辨的降级结果。所有测试均基于本地模拟器与可注入假时钟，
**不依赖真实网络与外部凭据**。

## 1. 版本兼容规则

请求头指定版本：`X-Api-Version: v1 | v2`，并建议携带 `X-Caller-Id`、`X-Idempotency-Key`。

| 内部模型字段 | v1 字段 | v2 字段 | 说明 |
|---|---|---|---|
| `customerId` | `customer` | `customerId` | v2 重命名 |
| `itemName` | `item` | `itemName` | v2 重命名 |
| `quantity` | `qty` | `quantity` | v2 重命名，须为正整数 |
| `note` | —（不存在） | `note`（可选） | v2 新增 |

映射规则（代码：`versioning/V1Adapter`、`versioning/V2Adapter`）：

- **新增字段**：v2 的 `note` 可选；v1 不认识该字段，出现即拒绝（防止向前语义被静默吞掉）。
- **重命名字段**：旧名在新版本中已废弃。v2 请求里出现 `customer/item/qty` 一律拒绝，
  错误信息明确给出新字段名（`field 'customer' was renamed to 'customerId' in v2`）。
- **必填校验**：`customerId/itemName/quantity` 缺失、为空或非正整数 → `MISSING_REQUIRED_FIELD`。
- **未知/缺失版本**：`X-Api-Version` 缺失或不在 `[v1, v2]` → `UNSUPPORTED_VERSION`。
- **严格字段集**：未知字段、畸形 JSON、非对象 body → `INCOMPATIBLE_VERSION`。
- 各版本映射后得到统一内部模型 `model/InternalOrder`，下游与版本无关。

## 2. 限流与配额语义（`ratelimit/RateLimiter`）

按 `X-Caller-Id` 维度独立维护状态（缺失时为 `anonymous`），两层控制：

1. **令牌桶（瞬时限流）**：容量 `burstCapacity`，按 `refillPerSecond` 随时间确定性恢复，
   恢复公式 `tokens = min(capacity, tokens + elapsedMillis * refillPerSecond / 1000)`。
   令牌不足时拒绝为 `RATE_LIMITED`（瞬时，可稍后重试），并给出 `retryAfterMillis`。
2. **固定窗口配额（总量）**：每 `windowMillis` 最多 `quotaPerWindow` 次。
   窗口内用尽拒绝为 `QUOTA_EXHAUSTED`（需等待窗口重置），`retryAfterMillis` 为窗口剩余时间。

两者可区分：HTTP 均为 429，但 `code` 不同，且带 `Retry-After`（秒）与 `X-Retry-After-Ms` 头。

- 并发安全：每个调用方状态串行化，50 线程抢容量 5 的桶，恰好放行 5 个，不超发（见测试）。
- 时间源为可注入 `LongSupplier`，测试中用假时钟推进，结果确定、无 flaky。
- 默认策略：突发 5、1 token/s、每 60s 配额 100；可经构造参数/`setPolicy` 覆盖。

## 3. 错误分类与上游归一化

| 错误码 | HTTP | 含义 | 可否重试 |
|---|---|---|---|
| `UNSUPPORTED_VERSION` | 400 | 版本缺失/不支持 | 否 |
| `MISSING_REQUIRED_FIELD` | 400 | 必填字段缺失或非法 | 否（修正请求） |
| `INCOMPATIBLE_VERSION` | 400 | 废弃字段/未知字段/畸形 body | 否（按版本迁移） |
| `RATE_LIMITED` | 429 | 瞬时令牌不足 | 是，按 Retry-After |
| `QUOTA_EXHAUSTED` | 429 | 窗口配额耗尽 | 窗口重置后 |
| `UPSTREAM_TIMEOUT` | 504 | 上游超时（重试后仍失败） | 可安全重试 |
| `UPSTREAM_ERROR` | 502 | 上游错误状态（如 5xx） | 视情况 |
| `UPSTREAM_MALFORMED_RESPONSE` | 502 | 上游响应畸形无法解析 | 视情况 |
| `CIRCUIT_OPEN` | 503 | 依赖熔断中，未真正调用上游 | 是，按 Retry-After（冷却剩余） |

统一错误体：`{"requestId","code","message","retryAfterMillis"}`。

- 原始上游异常类名、堆栈、状态码只进服务日志，**不进入对外 message**（`web/GlobalExceptionHandler`）。
- 上游调用带超时控制（默认 300ms）；**仅超时**触发一次重试（共 2 次尝试），
  错误状态/畸形响应立即归一化返回，不重试。
- **重试不产生重复副作用**：请求携带同一 `X-Idempotency-Key`，本地模拟上游按键去重
  （`SimulatedUpstreamClient.effectCount` 可断言副作用恰好一次）；网关侧
  `IdempotencyStore` 保证同键重放直接返回首次凭证，不再调用上游。
- “已生效但响应挂起”场景（`TIMEOUT_AFTER_SIDE_EFFECT`）验证：重试只回放，不二次生效。

## 4. 依赖熔断与降级（`circuitbreaker/`）

在错误归一化之上，网关按**依赖维度**（当前为单一上游 `orders-upstream`）维护熔断器，
上游连续抖动时快速失败或降级，不再把每个请求拖到超时、也不再反复打上游。

### 状态机与迁移条件

```
CLOSED ──窗口内失败数 ≥ failureThreshold──> OPEN
OPEN   ──冷却 cooldownMillis 届满─────────> HALF_OPEN
HALF_OPEN ──任一探请求成功──> CLOSED（失败窗口清零）
HALF_OPEN ──任一探请求失败──> OPEN（冷却重新计时）
```

- **失败计数**：`UPSTREAM_TIMEOUT` / `UPSTREAM_ERROR` / `UPSTREAM_MALFORMED_RESPONSE`
  均计入；按请求最终结果计一次（重试不重复计）。滑动窗口 `windowMillis` 之外的旧失败自动过期。
- **OPEN**：`tryAcquirePermit` 拒绝，`retryAfterMillis` = 冷却剩余时间，对外为
  `CIRCUIT_OPEN`（503 + `Retry-After`），与 502/504 的上游真实失败可区分。
- **HALF_OPEN**：同时最多放行 `halfOpenPermits` 个试探请求，名额占满时其余请求仍按
  熔断拒绝。全部状态迁移在熔断器监视器锁内完成，并发下不会超发（测试：20 线程抢 2 个
  试探名额，恰好放行 2 个）。
- **状态回收**：每个维度的失败时间戳随窗口滑动自动清理；`CircuitBreakerRegistry`
  在访问时回收空闲超过 `stateTtlMillis` 的维度状态，长跑不会无上限堆积。

### 降级语义（与错误响应的区别）

请求携带 `X-Degrade-Allowed: true` 时，熔断期间返回 **200 + 降级体**而非错误：

```json
{"requestId":"...","version":"v2","internalOrderSummary":"c1/i1 x1",
 "replayed":false,"receipt":null,"degraded":true}
```

- `degraded=true` 且 `receipt=null`：一眼可分辨这是降级而非真实上游成功。
- **降级结果不写入幂等记录**：上游恢复后，同一 `X-Idempotency-Key` 的真实请求仍会
  真正生效且只生效一次（测试断言 `replayed=false`、`effectCount` 恰好 +1）。
- 未声明可降级的请求在熔断期间收到 `CIRCUIT_OPEN` 错误，语义与超时/错误/畸形完全区分。

### 可配置项（`CircuitBreakerConfig`）

| 配置 | 含义 | 默认值 |
|---|---|---|
| `failureThreshold` | 窗口内触发熔断的失败次数 | 5 |
| `windowMillis` | 失败统计滑动窗口 | 10s |
| `cooldownMillis` | 熔断冷却时长 | 30s |
| `halfOpenPermits` | 半开同时放行的试探数 | 1 |
| `stateTtlMillis` | 维度状态空闲回收阈值 | 600s |

时间源为可注入 `LongSupplier`（与限流器一致），测试用假时钟确定性推进冷却与窗口，
无真实等待、无 flaky。

## 5. 本地验证

```bash
# 运行全部测试（无网络、无外部凭据）
mvn test

# 仅看某组测试
mvn -Dtest=RateLimiterConcurrencyTest test        # 并发限流边界 / 令牌确定性恢复 / 配额区分
mvn -Dtest=VersionCompatibilityTest test          # v1/v2 映射、必填、废弃字段、未知版本
mvn -Dtest=UpstreamMappingTest test               # 超时/错误状态/畸形映射、幂等重试不重复
mvn -Dtest=CircuitBreakerTest test                # 熔断状态机：打开/半开试探/恢复/再熔断/并发限员/状态回收
mvn -Dtest=CircuitBreakerGatewayTest test         # 网关级：快速失败、降级可分辨且不污染幂等、无可选头照常
mvn -Dtest=CircuitBreakerWebTest test             # Web 层：CIRCUIT_OPEN->503+Retry-After、降级体 200
mvn -Dtest=ApplicationTests test                  # MockMvc：HTTP 状态、Retry-After、错误体

# 启动服务（随机本地端口）
mvn spring-boot:run

# 手动调用示例
curl -i -X POST http://localhost:$PORT/api/orders \
  -H 'Content-Type: application/json' \
  -H 'X-Api-Version: v2' -H 'X-Caller-Id: demo' -H 'X-Idempotency-Key: order-001' \
  -d '{"customerId":"cust-1","itemName":"book","quantity":2,"note":"gift"}'
```

### 可观测性

每个请求在日志中打印 `[requestId]`、命中的 `rule=` 与判定 `basis=`，覆盖版本适配、
限流/配额、幂等命中、熔断放行/拒绝/降级、上游超时重试与错误归一化；
单测同样打印这些信息便于追溯断言依据。

## 6. 主要包结构

```
versioning/       版本路由与 v1/v2 -> InternalOrder 适配
model/            统一内部模型
ratelimit/        令牌桶 + 固定窗口配额（按调用方，可注入时钟）
circuitbreaker/   按依赖维度的熔断器（滑动窗口/冷却/半开试探，可注入时钟）与状态注册回收
upstream/         UpstreamClient 抽象与本地故障注入模拟器
idempotency/      幂等凭证存储
gateway/          编排：映射 -> 限流 -> 幂等 -> 熔断判定 -> 上游（超时/重试/归一化）/ 降级
web/              REST 入口与统一异常处理
error/            错误码、ApiException、错误响应体
```
