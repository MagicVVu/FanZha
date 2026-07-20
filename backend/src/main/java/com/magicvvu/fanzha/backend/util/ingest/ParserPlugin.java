package com.magicvvu.fanzha.backend.util.ingest;

public interface ParserPlugin {
    boolean supports(String filename, String contentType);
    java.util.List<MessageRecord> parse(byte[] bytes);
}
