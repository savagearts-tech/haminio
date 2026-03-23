# HA MinIO Client

高可用 MinIO 客户端库，在标准 [MinIO Java SDK](https://github.com/minio/minio-java) 之上提供客户端负载均衡、故障转移、Bulkhead（隔离舱）隔离与可观测性，**不依赖任何代理层**，直连 MinIO 节点。

---

## 功能特性

| 特性 | 说明 |
|------|------|
| 负载均衡 | 轮询（Round-Robin）/ 随机（Random）两种策略，可配置 |
| 故障转移 | 网络异常或 5xx 时自动切换至其他健康节点，指数退避 + 随机抖动 |
| 熔断器 | 每个 Endpoint 独立维护 Circuit Breaker（CLOSED → OPEN → HALF_OPEN） |
| Bulkhead 隔离 | SMALL / LARGE 双连接池，按请求体大小路由，防止大文件上传阻塞小请求 |
| Multipart 会话亲和 | Multipart Upload 的所有分片自动 pin 到初始节点 |
| 健康探测 | 后台定期探测 `/minio/health/ready`，无侵入地更新端点状态 |
| 可观测性 | Micrometer 指标覆盖请求耗时、上传/下载字节数、连接池状态、端点健康 |

---

## 架构概览

```
┌──────────────────────────────────────────────────────────────────────┐
│                       HaMinioClient (Façade)                         │
│  putObject ──► 按 size 分类 ──► SMALL Client  或  LARGE Client        │
│  getObject / statObject / removeObject / listObjects ──► SMALL Client │
└──────────────┬───────────────────────────┬──────────────────────────┘
               │                           │
   ┌───────────▼──────────┐   ┌────────────▼─────────┐
   │  OkHttpClient(SMALL) │   │  OkHttpClient(LARGE)  │
   │  ConnectionPool      │   │  ConnectionPool        │
   │  MinioEventListener  │   │  MinioEventListener    │
   └───────────┬──────────┘   └────────────┬──────────┘
               │  Interceptor Chain（两个 OkHttpClient 共享同一套拦截器）
               ▼
   ┌──────────────────────────────────────────────┐
   │  LoadBalancingInterceptor                    │
   │  ① Multipart uploadId → 端点亲和              │
   │  ② 普通请求 → LoadBalancer.next()             │
   │  ③ 改写 URL Host 为目标 Endpoint              │
   └──────────────┬───────────────────────────────┘
                  ▼
   ┌──────────────────────────────────────────────┐
   │  FailoverInterceptor                         │
   │  ① 执行请求                                   │
   │  ② 5xx / IOException → 被动标记端点失败        │
   │  ③ 指数退避后切换到下一个健康端点重试           │
   └──────────────┬───────────────────────────────┘
                  ▼
   ┌──────────────────────────────────────────────┐
   │  DefaultEndpointManager                      │
   │  ・每个 Endpoint 独立 DefaultCircuitBreaker   │
   │  ・后台线程定期 GET /minio/health/ready        │
   │  ・healthyEndpoints() 供负载均衡器查询         │
   └──────────────────────────────────────────────┘
```

### 核心组件

```
io.github.haminio
├── client/
│   ├── HaMinioClient              # 对外 Façade（组合而非继承 MinioClient）
│   ├── HaMinioClientConfig        # 不可变配置，Builder 模式
│   ├── HaMinioClientFactory       # 工厂：组装所有组件并返回 HaMinioClient
│   ├── LoadBalancingStrategy      # ROUND_ROBIN / RANDOM 枚举
│   ├── RoundRobinLoadBalancer     # 原子计数器轮询，线程安全
│   └── RandomLoadBalancer         # ThreadLocalRandom 随机选取
├── endpoint/
│   ├── Endpoint                   # 不可变值对象（url + healthCheckUrl）
│   ├── DefaultEndpointManager     # 健康状态 + Circuit Breaker 管理
│   └── DefaultCircuitBreaker      # CLOSED / HALF_OPEN / OPEN 状态机
├── interceptor/
│   ├── LoadBalancingInterceptor   # URL 改写 + Multipart 亲和
│   ├── FailoverInterceptor        # 跨端点重试 + 被动通知
│   └── MultipartSessionRegistry   # uploadId → Endpoint 映射，并发安全
├── bulkhead/
│   └── BulkheadCategory           # 按 objectSize 分类 SMALL / LARGE
└── observability/
    ├── MinioEventListener          # OkHttp EventListener → Micrometer 计量
    └── ConnectionPoolMetrics       # ConnectionPool Gauge 注册
```

---

## 快速开始

### 依赖（Maven）

```xml
<dependency>
    <groupId>io.github.haminio</groupId>
    <artifactId>haminio-client</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 最简使用

```java
import io.github.haminio.client.*;
import io.minio.*;

import java.io.ByteArrayInputStream;
import java.util.List;

// 1. 构建配置（使用所有默认值）
HaMinioClientConfig config = HaMinioClientConfig.builder()
        .endpoints(List.of(
                "http://minio-node1:9000",
                "http://minio-node2:9000",
                "http://minio-node3:9000"))
        .credentials("ACCESS_KEY", "SECRET_KEY")
        .build();

// 2. 创建客户端
HaMinioClient client = HaMinioClientFactory.create(config);

// 3. 上传对象（< 5MB → SMALL 连接池；>= 5MB 或 size=-1 → LARGE 连接池）
byte[] data = "hello, ha-minio".getBytes();
client.putObject(PutObjectArgs.builder()
        .bucket("my-bucket")
        .object("hello.txt")
        .stream(new ByteArrayInputStream(data), data.length, -1)
        .contentType("text/plain")
        .build());

// 4. 下载对象
try (GetObjectResponse resp = client.getObject(
        GetObjectArgs.builder().bucket("my-bucket").object("hello.txt").build())) {
    byte[] content = resp.readAllBytes();
}

// 5. 查询元数据
StatObjectResponse stat = client.statObject(
        StatObjectArgs.builder().bucket("my-bucket").object("hello.txt").build());
System.out.println("size = " + stat.size());

// 6. 列举对象
Iterable<Result<Item>> items = client.listObjects(
        ListObjectsArgs.builder().bucket("my-bucket").prefix("hello").build());

// 7. 删除对象
client.removeObject(
        RemoveObjectArgs.builder().bucket("my-bucket").object("hello.txt").build());

// 8. 关闭客户端（停止健康探测后台线程）
client.close();
```

---

## 配置参考

```java
HaMinioClientConfig config = HaMinioClientConfig.builder()

        // ── 必填 ─────────────────────────────────────────────────────────
        .endpoints(List.of("http://node1:9000", "http://node2:9000"))
        .credentials("access-key", "secret-key")

        // ── 重试 & 故障转移 ────────────────────────────────────────────
        .maxRetries(3)                                   // 跨端点最大重试次数，默认 3
        .retryBaseDelay(Duration.ofMillis(200))          // 退避基准延迟，默认 200ms
                                                         // 实际延迟 = random(0, base * 2^attempt)

        // ── 健康探测 & 熔断器 ──────────────────────────────────────────
        .healthCheckInterval(Duration.ofSeconds(10))     // 探测间隔，默认 10s
        .circuitBreakerFailureThreshold(3)               // 连续失败 N 次后熔断，默认 3
        .circuitBreakerRecoveryThreshold(2)              // 恢复所需连续成功次数，默认 2

        // ── 负载均衡策略 ───────────────────────────────────────────────
        .loadBalancingStrategy(LoadBalancingStrategy.ROUND_ROBIN)  // 默认 ROUND_ROBIN

        // ── Bulkhead 分级阈值 ──────────────────────────────────────────
        .bulkheadSizeThresholdBytes(5L * 1024 * 1024)   // >= 5MB 走 LARGE 池，默认 5MB
                                                         // size == -1（未知）也走 LARGE

        // ── SMALL 连接池（元数据 / 小文件） ───────────────────────────
        .smallPoolMaxIdleConnections(20)                 // 默认 20
        .smallPoolKeepAliveDuration(Duration.ofMinutes(5))  // 默认 5min

        // ── LARGE 连接池（大文件 / 流式上传） ─────────────────────────
        .largePoolMaxIdleConnections(5)                  // 默认 5
        .largePoolKeepAliveDuration(Duration.ofMinutes(10)) // 默认 10min

        .build();
```

---

## 接入 Micrometer（可观测性）

```java
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;

var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

// 将 registry 传入工厂，自动注册所有指标
HaMinioClient client = HaMinioClientFactory.create(config, registry);
```

注册后自动暴露以下指标：

| 指标名 | 类型 | 标签 | 说明 |
|--------|------|------|------|
| `minio.client.request.duration` | Timer | `endpoint`, `method` | 单次请求端到端耗时 |
| `minio.client.request.failures` | Counter | `endpoint`, `method` | 请求级失败计数 |
| `minio.client.bytes.uploaded` | Counter | `endpoint` | 累计上传字节数 |
| `minio.client.bytes.downloaded` | Counter | `endpoint` | 累计下载字节数 |
| `minio.client.connect.failures` | Counter | `endpoint` | TCP 连接失败计数 |
| `minio.client.pool.idle.connections` | Gauge | `pool` (SMALL/LARGE) | OkHttp 池空闲连接数 |
| `minio.client.pool.total.connections` | Gauge | `pool` (SMALL/LARGE) | OkHttp 池总连接数 |
| `minio.client.endpoint.healthy` | Gauge | `endpoint` | 1 = CLOSED（健康），0 = OPEN（熔断） |

> 指标均通过 **OkHttp EventListener** 和 **ConnectionPool 公开 API** 采集，不使用拦截器或反射。

---

## 设计约束

- **零侵入**：`HaMinioClient` 通过组合持有 `MinioClient`，不修改 SDK 任何类
- **连接透明**：所有 TCP 连接生命周期由 OkHttp `ConnectionPool` 管理，库本身不维护连接
- **可重试性保证**：`FailoverInterceptor` 检测到 `RequestBody.isOneShot() == true` 时禁用重试，防止非幂等流被二次发送
- **Multipart 一致性**：`LoadBalancingInterceptor` 通过响应体中的 `<UploadId>` 将同一次 Multipart Upload 的所有后续请求（UploadPart / CompleteMultipartUpload / AbortMultipartUpload）固定到初始节点
- **直连架构**：客户端直接面向 MinIO 节点，无任何中间代理层

---

## 运行测试

```bash
# 所有单元测试 + 集成测试（无需 Docker，无任何外部依赖）
./mvnw verify

# 仅单元测试
./mvnw test

# 跳过集成测试
./mvnw verify -DskipITs=true
```

集成测试基于 **OkHttp MockWebServer** 实现完整的 S3 协议模拟（`MinioMockServer`），支持 GetBucketLocation、PutObject、HeadObject、GetObject、DeleteObject 等操作，测试运行时间 < 10 秒。

---

## 技术栈

| 组件 | 版本 |
|------|------|
| MinIO Java SDK | 8.5.9 |
| OkHttp | 4.12.0 |
| Micrometer Core | 1.12.4 |
| SLF4J API | 2.0.12 |
| JUnit 5 | 5.10.2 |
| Mockito | 5.11.0 |
| Java | 17+ |
