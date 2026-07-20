package com.magicvvu.fanzha.backend.util;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;

public final class DateUtil {

    private DateUtil() {
    }

    public static String format(LocalDateTime dateTime, String pattern) {
        if (dateTime == null) {
            return null;
        }
        return dateTime.format(DateTimeFormatter.ofPattern(pattern));
    }

    public static LocalDateTime parseLocalDateTime(String text, String pattern) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        return LocalDateTime.parse(text, DateTimeFormatter.ofPattern(pattern));
    }

    public static LocalDate parseLocalDate(String text, String pattern) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        return LocalDate.parse(text, DateTimeFormatter.ofPattern(pattern));
    }

    public static Date toDate(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        Instant instant = dateTime.atZone(ZoneId.systemDefault()).toInstant();
        return Date.from(instant);
    }

    public static LocalDateTime toLocalDateTime(Date date) {
        if (date == null) {
            return null;
        }
        return LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault());
    }
}
