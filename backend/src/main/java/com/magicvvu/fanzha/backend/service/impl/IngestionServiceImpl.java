package com.magicvvu.fanzha.backend.service.impl;

import com.magicvvu.fanzha.backend.controller.IngestionController;
import com.magicvvu.fanzha.backend.service.IngestionService;
import com.magicvvu.fanzha.backend.util.EncodingUtil;
import com.magicvvu.fanzha.backend.util.FingerprintUtil;
import com.magicvvu.fanzha.backend.util.SensitiveMasker;
import com.magicvvu.fanzha.backend.util.ingest.MessageRecord;
import com.magicvvu.fanzha.backend.util.ingest.ParserRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class IngestionServiceImpl implements IngestionService {
    private final ParserRegistry parserRegistry;

    @Override
    public List<IngestionController.FileResult> process(List<MultipartFile> files) {
        List<IngestionController.FileResult> results = new ArrayList<>();
        for (MultipartFile file : files) {
            String filename = file.getOriginalFilename();
            String contentType = file.getContentType();
            long size = file.getSize();
            if (size > 50L * 1024 * 1024) {
                throw new IllegalArgumentException("单文件最大 50 MB");
            }
            try {
                byte[] raw = file.getBytes();
                byte[] normalized = EncodingUtil.normalize(raw);
                String masked = SensitiveMasker.mask(new String(normalized, Charset.forName("UTF-8")));
                List<MessageRecord> records = parseSingle(filename, contentType, masked.getBytes(Charset.forName("UTF-8")));
                String fingerprint = FingerprintUtil.sha256Hex(normalized);
                results.add(new IngestionController.FileResult(
                        filename != null ? filename : "unknown",
                        size,
                        fingerprint,
                        records.size()
                ));
            } catch (Exception e) {
                throw new IllegalArgumentException("文件处理失败: " + (StringUtils.hasText(filename) ? filename : ""));
            }
        }
        return results;
    }

    @Override
    public List<MessageRecord> parseSingle(String filename, String contentType, byte[] bytes) {
        return parserRegistry.parse(filename, contentType, bytes);
    }
}
