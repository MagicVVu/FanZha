package com.magicvvu.fanzha.backend.util.ingest;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class ParserRegistry {
    private final List<ParserPlugin> plugins = new ArrayList<>();

    public ParserRegistry(List<ParserPlugin> discovered) {
        plugins.addAll(discovered);
    }

    public List<MessageRecord> parse(String filename, String contentType, byte[] bytes) {
        for (ParserPlugin p : plugins) {
            if (p.supports(filename, contentType)) {
                return p.parse(bytes);
            }
        }
        return new ArrayList<>();
    }
}
