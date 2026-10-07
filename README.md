# Versioned API Gateway（Java / Spring Boot）

对外 API 集成网关：同一入口 `/api/orders` 支持多版本共存，按调用方做并发限流与配额控制，
对本地模拟上游的超时/错误/畸形响应做归一化，并在上游持续抖动时按依赖维度熔断、
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
| `CIRCUIT_OPEN` | 503 | 上游依赖熔断中，请求未打到上游 | 是，按 Retry-After（冷却剩余时间） |

统一错误体：`{"requestId","code","message","retryAfterMillis"}`。

- 原始上游异常类名、堆栈、状态码只进服务日志，**不进入对外 message**（`web/GlobalExceptionHandler`）。
- 上游调用带超时控制（默认 300ms）；**仅超时**触发一次重试（共 2 次尝试），
  错误状态/畸形响应立即归一化返回，不重试。
- **重试不产生重复副作用**：请求携带同一 `X-Idempotency-Key`，本地模拟上游按键去重
  （`SimulatedUpstreamClient.effectCount` 可断言副作用恰好一次）；网关侧
  `IdempotencyStore` 保证同键重放直接返回首次凭证，不再调用上游。
- “已生效但响应挂起”场景（`TIMEOUT_AFTER_SIDE_EFFECT`）验证：重试只回放，不二次生效。

## 4. 熔断与降级（`circuitbreaker/`）

在错误归一化之上再加一层依赖熔断：上游连续抖动时不再让每个请求干等超时/重试，
而是快速失败或返回可分辨的降级结果，同时给上游留出恢复空间。

### 4.1 状态机与迁移条件

```
CLOSED --(滑动窗口 windowMillis 内失败数 >= failureThreshold)--> OPEN
OPEN   --(冷却 cooldownMillis 期满，下一次请求到达时)----------> HALF_OPEN
HALF_OPEN --(任一试探成功)--> CLOSED（失败统计清空，恢复正常）
HALF_OPEN --(任一试探失败)--> OPEN（冷却重新计时）
```

- **计入熔断的失败**：上游超时、错误状态、畸形响应（即 `UPSTREAM_TIMEOUT` /
  `UPSTREAM_ERROR` / `UPSTREAM_MALFORMED_RESPONSE` 三类）；限流拒绝、参数校验失败不计。
- **OPEN（熔断）**：请求不再打到上游。不允许降级的请求立即返回 `CIRCUIT_OPEN`（503），
  响应带 `Retry-After` / `X-Retry-After-Ms`（冷却剩余毫秒），与超时（504）/上游错误（502）明确可区分。
- **HALF_OPEN（试探）**：冷却期满后只放行最多 `halfOpenPermits` 个并发请求探路，
  其余请求仍按熔断处理（快速失败/降级）。试探许可由 `onSuccess/onFailure` 归还，
  计数在监视器锁内完成，并发下严格不超发（20 线程抢 2 个许可，恰好放行 2 个，见测试）。
- **按维度统计**：每个上游依赖（如 `orders-upstream`）独立维护状态机，互不影响
  （`CircuitBreakerRegistry`）。
- **状态回收**：失败时间戳按滑动窗口自然过期；维度空闲超过 `idleTtlMillis`
  （窗口早已滑过）即被回收，长期运行不会无上限堆积。
- **时间可注入**：熔断器与注册表都使用可注入 `LongSupplier` 时钟，
  测试用假时钟确定性推进冷却/窗口/回收，无 flaky。

### 4.2 降级语义

请求头 `X-Degradation-Allowed: true` 表示该请求可接受降级。熔断（或试探满员）时：

- **可降级请求**：返回 200，但响应体 `degraded=true`、`receipt={"upstreamOrderId":"NONE","status":"DEGRADED"}`，
  一眼可分辨这不是真实上游成功（真实成功 `degraded=false` 且 receipt 为真实凭证）。
- **不可降级请求**（默认）：返回 503 `CIRCUIT_OPEN` + `Retry-After`。
- **降级不污染幂等记录**：降级结果从不写入 `IdempotencyStore`。上游恢复后，
  同一 `X-Idempotency-Key` 的真实请求仍会真正打到上游并**只生效一次**
  （`SimulatedUpstreamClient.effectCount` 可断言），之后同键请求正常回放真实凭证。

### 4.3 可配置项（`CircuitBreakerConfig`）

| 配置项 | 含义 | 默认值 |
|---|---|---|
| `failureThreshold` | 窗口内触发熔断的失败次数阈值 | 3 |
| `windowMillis` | 失败统计滑动观察窗口 | 10 000 |
| `cooldownMillis` | 熔断后进入试探的冷却时长 | 5 000 |
| `halfOpenPermits` | 试探阶段同时放行的最大请求数 | 1 |
| `idleTtlMillis` | 维度状态空闲回收时间 | 60 000 |

经 `GatewayService` 构造参数注入 `CircuitBreakerRegistry(config, clock)` 即可覆盖；
默认构造使用 `CircuitBreakerConfig.defaults()` 与系统时钟。

## 5. 本地验证

```bash
# 运行全部测试（无网络、无外部凭据）
mvn test

# 仅看某组测试
mvn -Dtest=RateLimiterConcurrencyTest test        # 并发限流边界 / 令牌确定性恢复 / 配额区分
mvn -Dtest=VersionCompatibilityTest test          # v1/v2 映射、必填、废弃字段、未知版本
mvn -Dtest=UpstreamMappingTest test               # 超时/错误状态/畸形映射、幂等重试不重复
mvn -Dtest=CircuitBreakerTest test                # 熔断打开/试探限流/恢复再熔断/降级可区分/并发不超发/窗口与回收
mvn -Dtest=ApplicationTests test                  # MockMvc：HTTP 状态、Retry-After、错误体、熔断 503 与降级 200、无可选头请求

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
限流/配额、幂等命中、熔断判定（`rule=circuit-breaker` 打印 PERMIT/REJECT 与状态）、
降级（`rule=degradation`）、上游超时重试与错误归一化；单测同样打印这些信息便于追溯断言依据。

## 6. 主要包结构

```
versioning/       版本路由与 v1/v2 -> InternalOrder 适配
model/            统一内部模型
ratelimit/        令牌桶 + 固定窗口配额（按调用方，可注入时钟）
circuitbreaker/   熔断状态机 + 按维度注册表（滑动窗口、冷却试探、空闲回收，可注入时钟）
upstream/         UpstreamClient 抽象与本地故障注入模拟器
idempotency/      幂等凭证存储（只存真实上游成功，不含降级结果）
gateway/          编排：映射 -> 限流 -> 幂等 -> 熔断判定 -> 上游（超时/重试/归一化）
web/              REST 入口与统一异常处理
error/            错误码、ApiException、错误响应体
```
