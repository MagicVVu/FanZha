package com.magicvvu.fanzha.backend.util;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.PutObjectResult;
import com.magicvvu.fanzha.backend.config.OssConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

@Component
@RequiredArgsConstructor
public class OssUtil {

    private final OSS ossClient;
    private final OssConfig.OssProperties ossProperties;

    public PutObjectResult upload(InputStream inputStream, String objectName) {
        return ossClient.putObject(ossProperties.getBucketName(), objectName, inputStream);
    }

    public PutObjectResult upload(byte[] bytes, String objectName) {
        return upload(new ByteArrayInputStream(bytes), objectName);
    }

    public byte[] download(String objectName) throws IOException {
        OSSObject ossObject = ossClient.getObject(ossProperties.getBucketName(), objectName);
        try (InputStream input = ossObject.getObjectContent();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = input.read(buffer)) != -1) {
                output.write(buffer, 0, len);
            }
            return output.toByteArray();
        }
    }

    public void delete(String objectName) {
        ossClient.deleteObject(ossProperties.getBucketName(), objectName);
    }
}
