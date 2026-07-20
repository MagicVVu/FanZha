package com.magicvvu.fanzha.backend.util.ingest;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class JsonParser implements ParserPlugin {
    @Override
    public boolean supports(String filename, String contentType) {
        String f = filename != null ? filename.toLowerCase() : "";
        String c = contentType != null ? contentType.toLowerCase() : "";
        return f.endsWith(".json") || c.contains("application/json");
    }

    @Override
    public List<MessageRecord> parse(byte[] bytes) {
        String s = new String(bytes, java.nio.charset.StandardCharsets.UTF_8).trim();
        List<MessageRecord> out = new ArrayList<>();
        if (s.startsWith("[")) {
            String[] parts = s.replace("\r\n", "\n").split("\\n");
            for (String p : parts) {
                String t = p.trim();
                if (!t.isEmpty()) out.add(new MessageRecord(t, null, null, null));
            }
        } else {
            out.add(new MessageRecord(s, null, null, null));
        }
        return out;
    }
}
