package org.ignis.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class S3Operations implements Closeable {
    private static final Logger LOGGER = LoggerFactory.getLogger(S3Operations.class);
    private final ObjectMapper mapper = new ObjectMapper();

    private static final String JOBS_PREFIX = "jobs/";
    private static final String DEFAULT_BUNDLE_FILENAME = "bundle.tar.gz";

    private final S3Client s3;

    public S3Operations(S3Client s3) {
        this.s3 = s3;
    }

    private String uploadToS3(String bucket, String key, RequestBody body) throws ISchedulerException {
        try{
            PutObjectRequest put = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build();
            s3.putObject(put, body);
            LOGGER.info("Uploaded to S3: {}", key);
            return key;
        } catch (Exception e) {
            throw new ISchedulerException("Failed to upload to S3: " + key, e);
        }
    }

    public String uploadJobBundle(String bucket, String jobId, byte[] bundleData) throws ISchedulerException {
        validateUploadParams(bucket, jobId, DEFAULT_BUNDLE_FILENAME, bundleData);
        String key = buildKey(jobId, DEFAULT_BUNDLE_FILENAME);
        return uploadToS3(bucket, key, RequestBody.fromBytes(bundleData));
    }

    public String uploadLargeFile(String bucket, String jobId, String relativePath, Path localPath) throws ISchedulerException {
        if (!Files.exists(localPath) || !Files.isRegularFile(localPath)) {
            throw new IllegalArgumentException("Large file does not exist: " + localPath);
        }
        String key = JOBS_PREFIX + jobId + "/payload/large/" + stripLeadingSlash(relativePath);
        return uploadToS3(bucket, key, RequestBody.fromFile(localPath));
    }

    private String stripLeadingSlash(String p) {
        return (p == null || p.isEmpty()) ? "" : p.replaceFirst("^/+", "");
    }

    private void validateUploadParams(String bucket, String jobId, String filename, byte[] data){
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("Data is empty to upload to S3");
        }
        if(filename == null || filename.trim().isEmpty()) {
            throw new IllegalArgumentException("Filename is empty to upload to S3");
        }
        if(bucket == null || bucket.trim().isEmpty()) {
            throw new IllegalArgumentException("Bucket is empty to upload to S3");
        }
        if(jobId == null || jobId.trim().isEmpty()) {
            throw new IllegalArgumentException("JobId is empty to upload to S3");
        }
    }

    private String buildKey(String jobId, String fileName){
        String cleanFileName = fileName.trim().replaceAll("^/+", "").replaceAll("/+$", "");
        return JOBS_PREFIX + jobId + "/" + cleanFileName;
    }

    // Reference: [41]
    private List<S3Object> listObjectsInBucket(String bucket, String prefix) throws ISchedulerException {
        if(bucket == null || bucket.trim().isEmpty()) {
            throw new IllegalArgumentException("Bucket is empty to list S3 Objects");
        }

        String effectivePrefix = (prefix != null) ? prefix.trim() : "";
        if (effectivePrefix.startsWith("/")) {
            effectivePrefix = effectivePrefix.substring(1);
        }
        if (effectivePrefix.endsWith("/")) {
            effectivePrefix = effectivePrefix.substring(0, effectivePrefix.length() - 1);
        }

        try{
        String nextContinuationToken = null;
        List<S3Object> contents = new ArrayList<>();

            do{
                ListObjectsV2Request.Builder requestBuilder = ListObjectsV2Request.builder()
                        .bucket(bucket)
                        .prefix(effectivePrefix);

                if(nextContinuationToken != null) {
                    requestBuilder.continuationToken(nextContinuationToken);
                }

                ListObjectsV2Response response = s3.listObjectsV2(requestBuilder.build());

                for(S3Object s3Object : response.contents()) {
                    if(!s3Object.key().endsWith("/")) {
                        contents.add(s3Object);
                    }
                }
                nextContinuationToken = response.nextContinuationToken();

            }while(nextContinuationToken != null);
            LOGGER.debug("Listing S3 Objects: {}", contents);

            return contents;
        }catch (NoSuchBucketException e) {
            throw new ISchedulerException("Bucket not exists: " + bucket, e);
        } catch (S3Exception e) {
            LOGGER.error("Error listing S3 Objects: {}", e.getMessage());
            throw new ISchedulerException("Error listing S3 Objects: " + e.awsErrorDetails().errorMessage(), e);
        } catch (Exception e) {
            LOGGER.error("Unexpected error listing S3 Objects: {}", e.getMessage());
            throw new ISchedulerException("Unexpected error listing S3 Objects", e);
        }
    }

    private void validateDownloadParams(String bucket, String prefix, String localDir) throws ISchedulerException {
        if(bucket == null || bucket.trim().isEmpty()) {
            throw new IllegalArgumentException("Bucket is empty to download S3 Objects");
        }
        if(prefix == null || prefix.trim().isEmpty()) {
            throw new IllegalArgumentException("Prefix is empty to download S3 Objects");
        }
        if(localDir == null || localDir.trim().isEmpty()) {
            throw new IllegalArgumentException("LocalDir is empty to download S3 Objects");
        }
    }

    // Reference: [41]
    private int downloadObjects(String bucket, String prefix, String localDir) throws ISchedulerException {
       validateDownloadParams(bucket, prefix, localDir);
       Path basePath = Paths.get(localDir);
       try{
           Files.createDirectories(basePath);
       } catch (IOException e){
           throw new ISchedulerException("Error creating directory: " + localDir, e);
       }

       List<S3Object> objects = listObjectsInBucket(bucket, prefix);
       int succesCount = 0;

        try{


        for(S3Object s3Object : objects) {
               String key =  s3Object.key();

           String relativePath = key.substring(prefix.length());
           if(relativePath.isEmpty()) continue;

           Path targetPath = basePath.resolve(relativePath);

           try{
               Files.createDirectories(targetPath.getParent());

               GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                       .bucket(bucket)
                       .key(key)
                       .build();

               s3.getObject(getObjectRequest, targetPath);
               LOGGER.info("Downloaded S3 Object: {}", targetPath);
               succesCount++;

           } catch (Exception e){
               throw new ISchedulerException("Error downloading", e);
           }
        }
       } catch(Exception e){
            throw new ISchedulerException("Error downloading S3 Objects", e);
        }
       LOGGER.debug("Download S3 Objects: {}", objects);
       return succesCount;
    }

    public void downloadJob(String jobId, String bucket) throws ISchedulerException {
        if (jobId == null || jobId.trim().isEmpty()) {
            throw new IllegalArgumentException("jobId should not be empty");
        }

        String prefix = "jobs/" + jobId.trim() + "/results/";

        String configuredDir = System.getenv("IGNIS_DOWNLOAD_DIR");
        String localDir;
        if (configuredDir != null && !configuredDir.trim().isEmpty()) {
            localDir = configuredDir.trim();
            LOGGER.debug("Using directory configured as environment variable: {}", localDir);
        } else {
            localDir = Paths.get("").toAbsolutePath().toString();
            LOGGER.debug("IGNIS_DOWNLOAD_DIR not found. Using current directory: {}", localDir);
        }

        try {
            Files.createDirectories(Paths.get(localDir));
            LOGGER.info("Downloading results for job {} → target: {}", jobId, localDir);
            int count = downloadObjects(bucket, prefix, localDir);
            LOGGER.info("Download completed: {} objects in {}", count, localDir);
        } catch (IOException e) {
            throw new ISchedulerException("The download directory could not be created: " + localDir, e);
        } catch (Exception e) {
            throw new ISchedulerException("Failure downloading results for job " + jobId, e);
        }
    }

    public void putString(String bucket, String key, String content, String contentType) throws ISchedulerException {
        try{
            s3.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .contentType(contentType)
                            .build(),
                    RequestBody.fromString(content)
            );
        }catch (Exception e){
            throw new ISchedulerException("Failed to write s3://" + bucket + "/" + key, e);
        }
    }

    public String getString(String bucket, String key) throws ISchedulerException {
        try{
            ResponseBytes<GetObjectResponse> bytes = s3.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(bucket).key(key).build());
            return bytes.asUtf8String();
        } catch(NoSuchKeyException e) {return null;}
          catch (S3Exception e) {
              if (e.statusCode() == 404) return null;
              throw new ISchedulerException("Failed to read s3://" + bucket + "/" + key + " (" + e.awsErrorDetails().errorMessage() + ")", e);
          } catch (Exception e) {
            throw new ISchedulerException("Failed to read s3://" + bucket + "/" + key, e);
        }

    }

    public void saveJobMetaToS3(JobMeta meta) throws ISchedulerException {
        try{
            String json = mapper.writeValueAsString(meta);
            putString(meta.bucket(), jobMetaKey(meta.jobId()), json, "application/json");
        } catch (Exception e){
            throw new ISchedulerException("Failed to upload job meta to S3 for job " + meta.jobId(), e);
        }
    }

    private String jobMetaKey(String jobId){
        return "jobs/" + jobId + "/job-meta.json";
    }

    public JobMeta loadJobMetaFromS3(String jobId, String bucket) {
        try {
            String key = jobMetaKey(jobId);
            String json = getString(bucket, key);
            if (json == null || json.isBlank()) return null;
            return mapper.readValue(json, JobMeta.class);
        } catch (Exception e) {
            LOGGER.warn("Could not load job meta from S3 for job {}", jobId, e);
            return null;
        }
    }

    @Override
    public void close(){
        s3.close();
    }
}
