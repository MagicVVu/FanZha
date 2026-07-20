package com.magicvvu.fanzha.backend.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SensitiveMasker {
    private static final Pattern PHONE = Pattern.compile("\\b(1\\d{10})\\b");
    private static final Pattern EMAIL = Pattern.compile("\\b([A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,})\\b");

    public static String mask(String input) {
        if (input == null || input.isEmpty()) return input;
        String result = input;
        Matcher pm = PHONE.matcher(result);
        StringBuffer pb = new StringBuffer();
        while (pm.find()) {
            String s = pm.group(1);
            String r = s.substring(0, 3) + "****" + s.substring(7);
            pm.appendReplacement(pb, Matcher.quoteReplacement(r));
        }
        pm.appendTail(pb);
        result = pb.toString();

        Matcher em = EMAIL.matcher(result);
        StringBuffer eb = new StringBuffer();
        while (em.find()) {
            String s = em.group(1);
            int at = s.indexOf('@');
            String local = s.substring(0, at);
            String domain = s.substring(at);
            String l = local.length() <= 2 ? "*" : local.substring(0, 2) + "***";
            em.appendReplacement(eb, Matcher.quoteReplacement(l + domain));
        }
        em.appendTail(eb);
        return eb.toString();
    }
}
