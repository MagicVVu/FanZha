package com.magicvvu.fanzha.backend.util.ingest;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class TxtParser implements ParserPlugin {
    @Override
    public boolean supports(String filename, String contentType) {
        String f = filename != null ? filename.toLowerCase() : "";
        String c = contentType != null ? contentType.toLowerCase() : "";
        return f.endsWith(".txt") || c.startsWith("text/plain");
    }

    @Override
    public List<MessageRecord> parse(byte[] bytes) {
        String s = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        String[] lines = s.split("\\r?\\n");
        List<MessageRecord> out = new ArrayList<>();
        for (String line : lines) {
            String t = line.trim();
            if (!t.isEmpty()) {
                out.add(new MessageRecord(t, null, null, null));
            }
        }
        return out;
    }
}
