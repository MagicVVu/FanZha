package com.magicvvu.fanzha.backend.util.ingest;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class HtmlParser implements ParserPlugin {
    @Override
    public boolean supports(String filename, String contentType) {
        String f = filename != null ? filename.toLowerCase() : "";
        String c = contentType != null ? contentType.toLowerCase() : "";
        return f.endsWith(".html") || f.endsWith(".htm") || c.contains("text/html");
    }

    @Override
    public List<MessageRecord> parse(byte[] bytes) {
        String s = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        String text = s.replaceAll("(?s)<script.*?</script>", "")
                .replaceAll("(?s)<style.*?</style>", "")
                .replaceAll("<[^>]+>", " ")
                .replaceAll("\\s+", " ")
                .trim();
        List<MessageRecord> out = new ArrayList<>();
        if (!text.isEmpty()) out.add(new MessageRecord(text, null, null, null));
        return out;
    }
}
