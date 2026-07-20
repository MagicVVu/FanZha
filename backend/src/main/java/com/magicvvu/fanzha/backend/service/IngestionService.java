package com.magicvvu.fanzha.backend.service;

import com.magicvvu.fanzha.backend.controller.IngestionController;
import com.magicvvu.fanzha.backend.util.ingest.MessageRecord;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface IngestionService {
    List<IngestionController.FileResult> process(List<MultipartFile> files);
    List<MessageRecord> parseSingle(String filename, String contentType, byte[] bytes);
}
