package com.magicvvu.fanzha.backend.util.ingest;

import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
public class ZipParser implements ParserPlugin {
    private final ParserRegistry registry;
    public ZipParser(ParserRegistry registry) {
        this.registry = registry;
    }

    @Override
    public boolean supports(String filename, String contentType) {
        String f = filename != null ? filename.toLowerCase() : "";
        String c = contentType != null ? contentType.toLowerCase() : "";
        return f.endsWith(".zip") || c.contains("application/zip");
    }

    @Override
    public List<MessageRecord> parse(byte[] bytes) {
        List<MessageRecord> out = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = zis.read(buffer)) > 0) {
                    baos.write(buffer, 0, n);
                }
                byte[] buf = baos.toByteArray();
                out.addAll(registry.parse(name, null, buf));
                zis.closeEntry();
            }
        } catch (Exception ignored) {
        }
        return out;
    }
}
