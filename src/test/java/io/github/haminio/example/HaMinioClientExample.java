package io.github.haminio.example;

import io.github.haminio.client.HaMinioClient;
import io.github.haminio.client.HaMinioClientConfig;
import io.github.haminio.client.HaMinioClientFactory;
import io.github.haminio.client.LoadBalancingStrategy;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * 演示如何通过 {@link HaMinioClient}（HA 高可用封装）访问本地 MinIO 服务。
 *
 * <h2>与 {@link MinioQuickstartExample} 的区别</h2>
 * <ul>
 *   <li>支持多个 MinIO 端点（Endpoint），具备负载均衡与自动故障转移能力</li>
 *   <li>内置 Bulkhead 隔离：小对象走 SMALL 连接池，大对象走 LARGE 连接池</li>
 *   <li>断路器保护：端点连续失败达阈值后自动熔断，恢复后自动半开</li>
 *   <li>Bucket 操作（makeBucket / bucketExists）须使用底层 SDK；
 *       HaMinioClient 仅封装对象级别操作（putObject / getObject / statObject 等）</li>
 * </ul>
 *
 * <h2>前提条件</h2>
 * <ol>
 *   <li>至少一个 MinIO 实例在本地运行</li>
 *   <li>Docker 快速启动：
 *       <pre>{@code
 * docker run -p 9000:9000 -p 9001:9001 \
 *   -e MINIO_ROOT_USER=minioadmin \
 *   -e MINIO_ROOT_PASSWORD=minioadmin \
 *   quay.io/minio/minio server /data --console-address ":9001"
 *       }</pre>
 *   </li>
 * </ol>
 */
public class HaMinioClientExample {

    // ── 配置：优先读取环境变量，未设置时回退到默认值 ──────────────────────────
    // 若本地 MinIO 凭据非默认值，请设置环境变量：
    //   Windows PowerShell: $env:MINIO_ACCESS_KEY="yourkey"; $env:MINIO_SECRET_KEY="yoursecret"
    //   Linux/macOS:        export MINIO_ACCESS_KEY=yourkey MINIO_SECRET_KEY=yoursecret
    private static final List<String> ENDPOINTS  = List.of(
            System.getenv().getOrDefault("MINIO_ENDPOINT", "http://localhost:9000"));
    private static final String ACCESS_KEY  =
            System.getenv().getOrDefault("MINIO_ACCESS_KEY", "minioadmin");
    private static final String SECRET_KEY  =
            System.getenv().getOrDefault("MINIO_SECRET_KEY", "minioadmin");
    private static final String BUCKET_NAME      = "ha-example-bucket";
    private static final String OBJECT_KEY       = "demo/greeting.txt";
    private static final String OBJECT_CONTENT   = "Hello from HaMinioClient (HA Edition)! 🚀";

    public static void main(String[] args) throws Exception {

        // ── 1. 构建 HaMinioClientConfig ───────────────────────────────────────
        HaMinioClientConfig config = HaMinioClientConfig.builder()
                // 支持多端点，此处以单节点为例
                .endpoints(ENDPOINTS)
                .credentials(ACCESS_KEY, SECRET_KEY)
                // 负载均衡策略：ROUND_ROBIN（轮询）或 RANDOM（随机）
                .loadBalancingStrategy(LoadBalancingStrategy.ROUND_ROBIN)
                // 端点健康检查间隔
                .healthCheckInterval(Duration.ofSeconds(10))
                // 单次请求最大重试次数（跨端点）
                .maxRetries(3)
                // 重试退避基础延迟（指数退避 + 抖动）
                .retryBaseDelay(Duration.ofMillis(200))
                // 断路器：连续 3 次失败开启熔断，连续 2 次成功关闭熔断
                .circuitBreakerFailureThreshold(3)
                .circuitBreakerRecoveryThreshold(2)
                // Bulkhead 阈值：>= 5MB 的对象路由到 LARGE 连接池
                .bulkheadSizeThresholdBytes(5L * 1024 * 1024)
                .build();

        // ── 2. 创建 HaMinioClient ─────────────────────────────────────────────
        // try-with-resources 确保客户端关闭时停止端点健康检查线程
        try (HaMinioClient haClient = HaMinioClientFactory.create(config)) {

            System.out.println("✅ HaMinioClient 已就绪，端点: " + ENDPOINTS);
            System.out.println("🔑  使用凭据 AccessKey=" + ACCESS_KEY
                    + (System.getenv("MINIO_ACCESS_KEY") != null ? " (来自环境变量)" : " (默认值，如有误请设置 MINIO_ACCESS_KEY)"));

            // ── 3. Bucket 操作：使用原生 MinioClient 创建 Bucket ─────────────
            //   HaMinioClient 专注于对象操作；Bucket 管理（通常仅在启动时执行）
            //   可通过原生 SDK 直接操作。
            ensureBucketExists(ENDPOINTS.get(0), ACCESS_KEY, SECRET_KEY, BUCKET_NAME);

            // ── 4. 上传对象（putObject） ──────────────────────────────────────
            byte[] payload = OBJECT_CONTENT.getBytes(StandardCharsets.UTF_8);

            try (InputStream inputStream = new ByteArrayInputStream(payload)) {
                haClient.putObject(
                        PutObjectArgs.builder()
                                .bucket(BUCKET_NAME)
                                .object(OBJECT_KEY)
                                .stream(inputStream, payload.length, -1)
                                .contentType("text/plain; charset=utf-8")
                                .build());
            }
            System.out.println("📤  对象上传成功: " + OBJECT_KEY);

            // ── 5. 查询对象元信息（statObject） ──────────────────────────────
            StatObjectResponse stat = haClient.statObject(
                    StatObjectArgs.builder()
                            .bucket(BUCKET_NAME)
                            .object(OBJECT_KEY)
                            .build());

            System.out.printf("📋  对象元信息 → size=%d bytes, etag=%s%n",
                    stat.size(), stat.etag());

            // ── 6. 下载并读取对象内容（getObject） ───────────────────────────
            try (GetObjectResponse response = haClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(BUCKET_NAME)
                            .object(OBJECT_KEY)
                            .build())) {

                String content = new String(response.readAllBytes(), StandardCharsets.UTF_8);
                System.out.println("📥  读取对象内容: " + content);
            }

            System.out.println("\n✅ HA 示例运行完成！");

        } // haClient.close() 在此处自动调用，停止后台健康检查
    }

    /**
     * 通过原生 MinioClient 确保 Bucket 存在（不存在则创建）。
     * Bucket 初始化通常只需执行一次，与 HaMinioClient 生命周期解耦。
     */
    private static void ensureBucketExists(String endpoint, String accessKey,
                                           String secretKey, String bucket) throws Exception {
        MinioClient adminClient = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();

        boolean exists = adminClient.bucketExists(
                BucketExistsArgs.builder().bucket(bucket).build());

        if (exists) {
            System.out.println("ℹ️  Bucket 已存在: " + bucket);
        } else {
            adminClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            System.out.println("🪣  Bucket 创建成功: " + bucket);
        }
    }
}
