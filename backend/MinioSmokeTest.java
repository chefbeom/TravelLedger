import io.minio.*;
import io.minio.messages.*;
public class MinioSmokeTest {
  public static void main(String[] args) throws Exception {
    String endpoint = System.getenv("MINIO_API");
    String accessKey = System.getenv("MINIO_NAME");
    String secretKey = System.getenv("MINIO_SECRET");
    String bucket = System.getenv("MINIO_CLOUD_BUCKET");
    MinioClient client = MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build();
    System.out.println("endpoint=" + endpoint);
    System.out.println("bucket=" + bucket);
    try {
      boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
      System.out.println("bucketExists=" + exists);
    } catch (Exception e) {
      System.out.println("bucketExistsError=" + e.getClass().getName() + ":" + e.getMessage());
    }
    try {
      client.putObject(PutObjectArgs.builder().bucket(bucket).object("travel-media/smoke-test.txt").stream(new java.io.ByteArrayInputStream("hello".getBytes(java.nio.charset.StandardCharsets.UTF_8)), 5, -1).contentType("text/plain").build());
      System.out.println("putObject=OK");
    } catch (Exception e) {
      System.out.println("putObjectError=" + e.getClass().getName() + ":" + e.getMessage());
      e.printStackTrace(System.out);
    }
    try {
      String url = client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder().method(io.minio.http.Method.PUT).bucket(bucket).object("travel-media/smoke-presign.txt").expiry(600).build());
      System.out.println("presignedUrl=OK");
      System.out.println(url);
    } catch (Exception e) {
      System.out.println("presignedUrlError=" + e.getClass().getName() + ":" + e.getMessage());
      e.printStackTrace(System.out);
    }
  }
}
