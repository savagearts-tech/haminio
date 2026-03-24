package io.github.haminio.example;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveBucketArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 演示如何使用 MinIO Java SDK 访问本地 MinIO 服务。
 *
 * <h2>前提条件</h2>
 * <ol>
 * <li>启动本地 MinIO 服务，默认监听 http://localhost:9000</li>
 * <li>AccessKey / SecretKey 默认均为 {@code minioadmin}（Docker 快速启动默认值）</li>
 * </ol>
 *
 * <h2>快速启动 MinIO（Docker）</h2>
 * 
 * <pre>{@code
 * docker run -p 9000:9000 -p 9001:9001 \
 *   -e MINIO_ROOT_USER=minioadmin \
 *   -e MINIO_ROOT_PASSWORD=minioadmin \
 *   quay.io/minio/minio server /data --console-address ":9001"
 * }</pre>
 *
 * <p>
 * 注意：这是一个可执行示例（main 方法），不是 JUnit 测试，
 * 需要有真实 MinIO 实例运行时才能成功执行。
 */
public class MinioQuickstartExample {

        // ── 配置：优先读取环境变量，未设置时回退到默认值 ─────────────────────────
        // Windows PowerShell: $env:MINIO_ACCESS_KEY="yourkey";
        // $env:MINIO_SECRET_KEY="yoursecret"
        private static final String MINIO_ENDPOINT = System.getenv().getOrDefault("MINIO_ENDPOINT",
                        "http://localhost:9000");
        private static final String ACCESS_KEY = System.getenv().getOrDefault("MINIO_ACCESS_KEY", "minioadmin");
        private static final String SECRET_KEY = System.getenv().getOrDefault("MINIO_SECRET_KEY", "minioadmin");
        private static final String BUCKET_NAME = "my-example-bucket";
        private static final String OBJECT_KEY = "hello/world.txt";
        private static final String OBJECT_CONTENT = "Hello from HaMinioClient example! 🎉";

        public static void main(String[] args) throws Exception {

                // ── 1. 构建 MinioClient ───────────────────────────────────────────────
                MinioClient client = MinioClient.builder()
                                .endpoint(MINIO_ENDPOINT)
                                .credentials(ACCESS_KEY, SECRET_KEY)
                                .build();

                System.out.println("✅ MinioClient 已连接至: " + MINIO_ENDPOINT);

                // ── 2. 创建 Bucket（若不存在） ────────────────────────────────────────
                boolean exists = client.bucketExists(
                                BucketExistsArgs.builder().bucket(BUCKET_NAME).build());

                if (exists) {
                        System.out.println("ℹ️  Bucket 已存在: " + BUCKET_NAME);
                        client.removeBucket(RemoveBucketArgs.builder().bucket(BUCKET_NAME).build());
                } else {
                        client.makeBucket(
                                        MakeBucketArgs.builder().bucket(BUCKET_NAME).build());
                        System.out.println("🪣  Bucket 创建成功: " + BUCKET_NAME);
                }

                // ── 3. 上传对象 ───────────────────────────────────────────────────────
                byte[] payload = OBJECT_CONTENT.getBytes(StandardCharsets.UTF_8);

                try (InputStream inputStream = new ByteArrayInputStream(payload)) {
                        client.putObject(
                                        PutObjectArgs.builder()
                                                        .bucket(BUCKET_NAME)
                                                        .object(OBJECT_KEY)
                                                        .stream(inputStream, payload.length, -1)
                                                        .contentType("text/plain; charset=utf-8")
                                                        .build());
                }
                System.out.println("📤  对象上传成功: " + OBJECT_KEY);

                // ── 4. 查询对象元信息（验证上传） ────────────────────────────────────
                StatObjectResponse stat = client.statObject(
                                StatObjectArgs.builder()
                                                .bucket(BUCKET_NAME)
                                                .object(OBJECT_KEY)
                                                .build());

                System.out.printf("📋  对象元信息 → size=%d bytes, etag=%s, contentType=%s%n",
                                stat.size(), stat.etag(), stat.contentType());

                // ── 5. 下载并读取对象内容 ─────────────────────────────────────────────
                try (GetObjectResponse response = client.getObject(
                                GetObjectArgs.builder()
                                                .bucket(BUCKET_NAME)
                                                .object(OBJECT_KEY)
                                                .build())) {

                        String content = new String(response.readAllBytes(), StandardCharsets.UTF_8);
                        System.out.println("📥  读取对象内容: " + content);
                }

                System.out.println("\n✅ 示例运行完成！");
        }
}
