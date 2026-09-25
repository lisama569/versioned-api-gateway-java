# 版本化对外 API 集成网关（本地模拟上游）

在同一个入口 `POST /api/orders` 下，让 v1/v2/v3 不同版本的调用方获得**一致且可解释**的结果：

- 多版本字段共存：新增 / 重命名 / 废弃字段按稳定规则映射到统一内部模型；
- 按调用方维度的并发限流 + 令牌桶速率 + 固定窗口配额，令牌随时间**确定性**恢复，并发不超发；
- 上游超时 / 错误状态 / 畸形响应归一化为可区分的对外错误码，**原始上游异常绝不外泄**；
- 幂等键保证同一笔提交的上游副作用**最多一次**，网关内部重试不会造成重复副作用。

所有上游依赖均为**本地模拟**（`MockUpstreamClient`），测试不依赖真实网络或外部凭据。

---

## 1. 统一入口与请求约定

| 项 | 值 |
|---|---|
| 路径 | `POST /api/orders`（所有版本共用） |
| 版本分期 | 请求头 `X-API-Version: v1 | v2 | v3`（大小写不敏感） |
| 调用方 | 请求头 `X-Caller-Id`（缺失按 `anonymous` 归组） |
| 请求标识 | 请求头 `X-Request-Id`（缺失由网关注入 `req-<uuid>`），回包/错误/日志均带回 |
| 幂等键 | 请求头 `Idempotency-Key`（可选；强烈建议提供） |
| 请求体 | JSON 对象 |

成功回包（所有版本结构一致）：

```json
{
  "orderId": "ORD-1b2c3",
  "idempotencyKey": "key-001",
  "productName": "widget",
  "quantity": 2,
  "totalAmount": 3.00,
  "currency": "USD",
  "status": "ACCEPTED"
}
```

错误回包（统一结构，原始上游细节不外泄）：

```json
{
  "requestId": "req-xxx",
  "code": "MISSING_REQUIRED_FIELD",
  "rule": "MISSING_REQUIRED_FIELD",
  "basis": "version=v3, field=currency, 自 v3 起 currency 为必填字段（v1/v2 缺省为 USD）",
  "message": "MISSING_REQUIRED_FIELD"
}
```

`rule` 表示命中的具体规则，`basis` 给出判定依据（字段路径、实际值、计数快照等），便于调用方区分拒绝原因。

---

## 2. 版本兼容规则

下单字段在三个版本间的演进（统一内部模型 `InternalOrder`）：

| 内部字段 | v1 | v2 | v3 |
|---|---|---|---|
| `productName` | `product_name`（必填） | `product_name`，**兼容旧别名 `name`**（弃用，日志标记） | `product_name`（必填）；`name` **已移除，出现即拒绝** |
| `quantity` | 整数，[1, 10000]，必填 | 同 v1 | 同 v1 |
| 单价 | `unit_price_cents`：整数，单位**分**，必填 | 同 v1 | **重命名**为 `unitPrice`：数字，单位**元**，最多两位小数；旧字段出现即拒绝 |
| `currency` | 无此字段，按规则补默认值 `USD` | 新增**可选**字段，缺省补 `USD` | 升级为**必填**，缺失拒绝（不再猜测默认值） |
| `remark` | 无 | 无 | 新增可选字段 |

稳定原则：

1. **新增字段**：旧版本不感知时，由映射层给出确定性的内部默认值（如 v1/v2 缺省币种），内部模型始终完整。
2. **重命名**：v2 为兼容期（旧别名 `name` 仍接受并映射到 `product_name`）；v3 切换到新名。
3. **废弃 / 移除**：v3 中 `name` 与 `unit_price_cents` 不再静默兼容，而是以 `DEPRECATED_FIELD` 明确拒绝，避免旧客户端误以为字段仍受支持。
4. **必填校验**：只做**版本感知**的拒绝——例如 `currency` 在 v1/v2 缺失合法、在 v3 缺失则拒绝，拒绝信息标注版本与字段。
5. 未知字段一律忽略（不破坏前向兼容）。

版本本身无法识别（缺失、空、非 `vN`）返回 `VERSION_UNSUPPORTED`，不进入任何业务映射。

---

## 3. 限流与配额语义

按 `X-Caller-Id` **独立计数**（互不影响），每个调用方依次经过三道判定；三项判定在“每调用方一把锁”内**原子**完成，因此并发下不会超发。判定顺序固定为：

1. **固定窗口配额（持久预算）**：窗口内累计被接纳请求数达到 `quotaPerWindow` 即拒绝。
   错误码 `QUOTA_EXHAUSTED`，窗口到点确定性轮换后恢复。
2. **令牌桶（瞬时速率）**：容量 `bucketCapacity`，按 `refillTokensPerSecond` 随毫秒线性补充，上限为桶容量（不累积）。桶空拒绝为 `RATE_LIMITED_TRANSIENT` / 规则 `TOKEN_BUCKET_EMPTY`。
3. **并发槽（在途上限）**：在途请求数达到 `maxConcurrent` 拒绝为 `RATE_LIMITED_TRANSIENT` / 规则 `CONCURRENCY_LIMIT`。请求处理结束（回包返回）时释放槽位。

关键语义：

- **只有被接纳的请求才扣减**令牌与配额；被拒绝的请求不消耗任何额度。
- 并发槽在请求结束时归还；**令牌与配额不回退**（反映真实接纳量）。
- **瞬时限流 vs 配额耗尽可区分**：前者是 429 `RATE_LIMITED_TRANSIENT`（稍后即可恢复），后者是 429 `QUOTA_EXHAUSTED`（必须等窗口轮换）。两者 HTTP 状态同为 429，但 `code` 不同。
- 时间来自可注入的 `Clock`：生产用墙钟，测试用 `MutableClock` 手动拨表，令牌恢复与窗口轮换可精确断言，无需 sleep 等待。

默认参数（可在 `application.properties` 覆盖）：

```properties
gateway.ratelimit.max-concurrent=3
gateway.ratelimit.bucket-capacity=5
gateway.ratelimit.refill-tokens-per-second=1.0
gateway.ratelimit.quota-per-window=20
gateway.ratelimit.window-millis=60000
```

---

## 4. 上游降级、错误分类与幂等

### 4.1 上游故障归一化

网关只对外暴露“分类”，不暴露上游异常类型 / 堆栈 / 原始报文：

| 上游情况 | 对外错误码 | HTTP | 规则 rule | 是否重试 |
|---|---|---|---|---|
| 读超时（`UpstreamException` TIMEOUT） | `UPSTREAM_TIMEOUT` | 504 | `UPSTREAM_TIMEOUT` | 否 |
| 非 2xx（如 500；503 见下） | `UPSTREAM_ERROR` | 502 | `UPSTREAM_ERROR_STATUS` | 否（503 除外） |
| 200 但响应体非法 JSON / 缺关键字段 / 空响应 | `UPSTREAM_MALFORMED` | 502 | `UPSTREAM_MALFORMED_BODY` / `UPSTREAM_EMPTY_RESPONSE` | 否 |
| 其他未受控上游异常 | `UPSTREAM_ERROR` | 502 | `UPSTREAM_UNEXPECTED` | 否 |
| 503 Service Unavailable | 先内部重试一次，仍失败再 `UPSTREAM_ERROR` | 502 | `UPSTREAM_ERROR_STATUS` | **是，仅一次** |

重试策略刻意收窄：只对上游明确表达“临时不可用”的 **503 重试一次**；超时、500、畸形响应均不重试（避免对失败副作用的盲目重放）。

### 4.2 幂等：重试不产生重复副作用

- 同一 `Idempotency-Key` 的上游调用在 `IdempotencyService` 中全局去重：**上游副作用最多一次**。
- 同键并发请求等待并共享首个请求的结果；首个请求成功后，后续重复 / 重试直接返回缓存结果，不再触达上游。
- 首个请求失败时，失败结果同样被缓存并重放（不会因客户端重试而重复打上游）。
- 因此“网关对 503 的内部重试”也在幂等包裹之内：成功路径下的 503→重试只会产生一次最终成功副作用。
- 不提供幂等键时每次都是独立调用（重复提交风险由调用方承担）。

### 4.3 本地模拟上游如何触发场景

`MockUpstreamClient` 通过**商品名前缀**脚本化触发故障，无需真实网络：

| `product_name` 前缀 | 行为 |
|---|---|
| `TIMEOUT:` | 抛超时异常 → `UPSTREAM_TIMEOUT(504)` |
| `STATUS_500:` | 返回 500 → `UPSTREAM_ERROR(502)` |
| `STATUS_503:` | 返回 503（每次） |
| `MALFORMED:` | 200 但返回非法 JSON → `UPSTREAM_MALFORMED(502)` |
| `OK_503:` | 同幂等键首次 503、第二次成功（验证安全重试） |
| 其他 | 200 + 合法 JSON |

---

## 5. 错误码总表

| code | HTTP | 含义 / 可区分点 |
|---|---|---|
| `VERSION_UNSUPPORTED` | 400 | 版本头缺失或无法识别 |
| `MISSING_REQUIRED_FIELD` | 400 | 该版本下必填字段缺失（basis 含版本与字段） |
| `DEPRECATED_FIELD` | 400 | 使用了该版本已移除 / 已重命名的旧字段（`FIELD_REMOVED_NAME` / `FIELD_RENAMED_UNIT_PRICE_CENTS`） |
| `INVALID_FIELD_VALUE` | 400 | 类型错误 / 取值越界 / 小数位超限 / 请求体不是合法 JSON |
| `RATE_LIMITED_TRANSIENT` | 429 | 瞬时限流：`TOKEN_BUCKET_EMPTY` 或 `CONCURRENCY_LIMIT`，稍后可恢复 |
| `QUOTA_EXHAUSTED` | 429 | 窗口配额耗尽，需等窗口轮换 |
| `UPSTREAM_TIMEOUT` | 504 | 上游超时（归一化） |
| `UPSTREAM_ERROR` | 502 | 上游错误状态 / 未分类上游错误 |
| `UPSTREAM_MALFORMED` | 502 | 上游响应畸形 |
| `INTERNAL_ERROR` | 500 | 兜底，内部细节仅进日志 |

---

## 6. 本地验证方法

需要 JDK 21 与 Maven。

```bash
# 编译
mvn -q compile

# 运行全部单测（28 个，纯本地、无网络、无外部凭据）
mvn test

# 只跑某一类
mvn test -Dtest=RateLimitServiceTest
mvn test -Dtest=OrderVersionMapperTest
mvn test -Dtest=UpstreamNormalizationTest
mvn test -Dtest=GatewayHttpIntegrationTest
```

测试覆盖与可观测性：

- **并发限流边界**：24 线程争抢并发上限 3，断言在途峰值恰好为 3 且不超过 3、必有瞬时限流、每个请求都被明确接纳或拒绝。
- **令牌确定性恢复 / 窗口轮换**：用 `MutableClock` 精确拨表（999ms 不出令牌、1000ms 恰好 1 个、补桶封顶容量；窗口前配额不恢复、到点恢复）。
- **瞬时限流 vs 配额耗尽**：分别断言 `RATE_LIMITED_TRANSIENT` 与 `QUOTA_EXHAUSTED`。
- **跨版本兼容**：v1 默认币种、v2 别名兼容、v3 元价换算与 currency 必填、旧字段拒绝、缺失 / 非法值区分。
- **上游异常映射**：超时 / 500 / 畸形 / 503 重试 / 重复提交幂等，并断言对外 basis 中不含原始上游异常类型。
- **日志**：单测与运行日志统一打印 `requestId`、命中的 `rule` 与判定依据 `basis`（如 `rate-limit decision=REJECT reason=QUOTA_EXHAUSTED caller=... quotaUsed=3/3`）。

### 手动 curl（启动后，默认随机端口；可在 application.properties 固定 server.port）

```bash
# v1 成功（currency 缺省补 USD）
curl -s -X POST localhost:8080/api/orders \
  -H 'X-Caller-Id: alice' -H 'X-API-Version: v1' -H 'X-Request-Id: r1' \
  -H 'Content-Type: application/json' \
  -d '{"product_name":"widget","quantity":2,"unit_price_cents":150}'

# v3：旧字段 name 已移除 -> DEPRECATED_FIELD
curl -i -X POST localhost:8080/api/orders \
  -H 'X-Caller-Id: alice' -H 'X-API-Version: v3' \
  -d '{"name":"x","quantity":1,"unitPrice":1,"currency":"USD"}'

# 上游超时 -> 504 UPSTREAM_TIMEOUT
curl -i -X POST localhost:8080/api/orders \
  -H 'X-Caller-Id: alice' -H 'X-API-Version: v1' \
  -d '{"product_name":"TIMEOUT:x","quantity":1,"unit_price_cents":1}'
```

---

## 7. 代码结构

```
common/error    GatewayErrorCode（错误码+HTTP状态）、GatewayException（code/rule/basis/requestId）
common/model    InternalOrder（统一内部模型）、OrderResult、RequestContext
version         ApiVersion、OrderVersionMapper（版本字段映射/校验规则）
ratelimit       Clock/SystemClock/MutableClock、RateLimitConfig、Permit、RateLimitService
upstream        UpstreamClient、MockUpstreamClient、UpstreamResponse、UpstreamException、UpstreamFailureKind
idempotency     IdempotencyService（同键副作用最多一次）
service         OrderGatewayService（版本→限流→幂等→上游→归一化 的编排与重试）
web             GatewayController（统一入口）、GlobalExceptionHandler、ErrorResponse
config          GatewayConfiguration（时钟、限流参数、本地模拟上游装配）
```

处理流水线：

```
请求头/请求体
  └─ 版本识别(VERSION_UNSUPPORTED)
      └─ 客户端 JSON 解析(INVALID_FIELD_VALUE)
          └─ 版本字段映射(MISSING_REQUIRED_FIELD / DEPRECATED_FIELD / INVALID_FIELD_VALUE)
              └─ 限流配额(QUOTA_EXHAUSTED / RATE_LIMITED_TRANSIENT)
                  └─ 幂等包裹的上游调用(503 安全重试一次)
                      └─ 上游归一化(UPSTREAM_TIMEOUT / UPSTREAM_ERROR / UPSTREAM_MALFORMED)
                          └─ 统一 OrderResult
```
