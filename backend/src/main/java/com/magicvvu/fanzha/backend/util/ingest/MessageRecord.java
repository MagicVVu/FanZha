package com.magicvvu.fanzha.backend.util.ingest;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class MessageRecord {
    private String text;
    private Long timestamp;
    private String contact;
    private String group;
}
