package com.magicvvu.fanzha.backend.service.fraud.http;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * In-memory jar so list pages (e.g. Sogou) can set cookies that follow on article requests.
 */
public class SimpleMemoryCookieJar implements CookieJar {

    private final List<Cookie> jar = Collections.synchronizedList(new ArrayList<>());

    private static String cookieKey(Cookie c) {
        return c.name() + "|" + c.domain() + "|" + c.path();
    }

    @Override
    public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
        if (cookies == null || cookies.isEmpty()) {
            return;
        }
        synchronized (jar) {
            for (Cookie incoming : cookies) {
                jar.removeIf(x -> cookieKey(x).equals(cookieKey(incoming)));
                jar.add(incoming);
            }
        }
    }

    @Override
    public List<Cookie> loadForRequest(HttpUrl url) {
        synchronized (jar) {
            long now = System.currentTimeMillis();
            jar.removeIf(c -> c.persistent() && c.expiresAt() <= now);
            List<Cookie> out = new ArrayList<>();
            for (Cookie c : jar) {
                if (c.matches(url)) {
                    out.add(c);
                }
            }
            return out;
        }
    }
}
