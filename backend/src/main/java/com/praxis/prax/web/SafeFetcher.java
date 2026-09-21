package com.praxis.prax.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

/**
 * Fetches a URL chosen by something other than us, without becoming a confused
 * deputy.
 *
 * THE THREAT. The backend sits inside the user's machine, behind no firewall,
 * next to services that trust anything that can reach them:
 *
 *   http://localhost:8086   its own admin surface
 *   http://localhost:11434  Ollama, unauthenticated
 *   localhost:5432          PostgreSQL
 *   169.254.169.254         cloud metadata, if this ever leaves the laptop
 *   file:///                the filesystem
 *
 * Search results are attacker-influenceable — through SEO, or through a poisoned
 * page that links onward. So a URL arriving here is untrusted input, and every
 * hop must be re-checked: a host that resolves publicly is free to answer with a
 * 302 to 127.0.0.1.
 *
 * KNOWN LIMIT, stated rather than hidden: this resolves the host and then lets
 * HttpClient connect, so a DNS entry that changes between the two could still
 * slip through (a TOCTOU rebind). Closing that needs a custom socket factory
 * pinned to the vetted address. For a single-user local tool the residual risk
 * is small, but it is real, and it is the first thing to fix if this ever runs
 * anywhere multi-tenant.
 */
@Component
public class SafeFetcher {

    private static final Logger log = LoggerFactory.getLogger(SafeFetcher.class);

    /** Enough hops for a canonical-URL redirect chain, few enough to bound the work. */
    static final int MAX_REDIRECTS = 3;
    static final int MAX_BYTES = 512 * 1024;
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    /** Honest about what this is. Some sites block unknown agents; that is their right. */
    static final String USER_AGENT =
            "PraxisChess/1.0 (local chess coach; +https://github.com/praxis-chess)";

    /**
     * Built on first use, not at construction.
     *
     * This is a @Component, so it is created at startup even when web research
     * is switched off — and building an HttpClient opens a selector and starts
     * threads. Deferring it means a disabled feature costs nothing, and it keeps
     * the SSRF tests free of any network requirement.
     */
    private volatile HttpClient http;

    private HttpClient http() {
        HttpClient local = http;
        if (local == null) {
            synchronized (this) {
                local = http;
                if (local == null) {
                    local = HttpClient.newBuilder()
                            .connectTimeout(CONNECT_TIMEOUT)
                            // Manual, so each hop is re-validated. Automatic
                            // following would check the first URL and then
                            // cheerfully land on 127.0.0.1.
                            .followRedirects(HttpClient.Redirect.NEVER)
                            .build();
                    http = local;
                }
            }
        }
        return local;
    }

    /** A fetched page, or empty when anything at all went wrong. */
    public record Page(String finalUrl, String contentType, String body) {}

    /**
     * Why a URL was refused. Returned rather than thrown — a blocked result is a
     * normal outcome here, not an exception.
     */
    public enum Refusal { OK, BAD_SYNTAX, BAD_SCHEME, UNRESOLVABLE, PRIVATE_ADDRESS }

    /**
     * The whole security decision, as a pure function of the URL.
     *
     * Separated from fetching on purpose: it needs to be exercised by tests
     * against dozens of addresses without a network in sight.
     */
    public static Refusal check(String url) {
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (Exception e) {
            return Refusal.BAD_SYNTAX;
        }
        String scheme = uri.getScheme();
        if (scheme == null) return Refusal.BAD_SCHEME;

        // Allow-list, never a block-list: file, ftp, gopher, data, jar and
        // whatever the JDK adds next are all refused by not being named.
        String s = scheme.toLowerCase(Locale.ROOT);
        if (!s.equals("http") && !s.equals("https")) return Refusal.BAD_SCHEME;

        String host = uri.getHost();
        if (host == null || host.isBlank()) return Refusal.BAD_SYNTAX;

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (Exception e) {
            return Refusal.UNRESOLVABLE;
        }
        if (addresses.length == 0) return Refusal.UNRESOLVABLE;

        // EVERY address, not just the first. A host answering with one public and
        // one loopback address must not be reachable through the public one.
        for (InetAddress a : addresses) {
            if (isPrivate(a)) return Refusal.PRIVATE_ADDRESS;
        }
        return Refusal.OK;
    }

    /**
     * Is this address somewhere the backend must never be pointed at?
     *
     * InetAddress covers most of it, but not all: CGNAT and IPv6 unique-local
     * have no predicate and are checked by hand.
     */
    static boolean isPrivate(InetAddress a) {
        if (a.isLoopbackAddress()      // 127/8, ::1
                || a.isAnyLocalAddress()   // 0.0.0.0, ::
                || a.isLinkLocalAddress()  // 169.254/16 — includes cloud metadata
                || a.isSiteLocalAddress()  // 10/8, 172.16/12, 192.168/16
                || a.isMulticastAddress()) {
            return true;
        }

        byte[] b = a.getAddress();

        if (b.length == 4) {
            int b0 = b[0] & 0xFF, b1 = b[1] & 0xFF;
            // 100.64.0.0/10 — carrier-grade NAT. Not "site local", still not ours.
            if (b0 == 100 && b1 >= 64 && b1 <= 127) return true;
            // 192.0.0.0/24 IETF protocol assignments
            if (b0 == 192 && b1 == 0 && (b[2] & 0xFF) == 0) return true;
            // 255.255.255.255 and the rest of 240/4 (reserved)
            if (b0 >= 240) return true;
            return false;
        }

        if (b.length == 16) {
            // fc00::/7 — unique local addresses
            if ((b[0] & 0xFE) == 0xFC) return true;
            // IPv4-mapped (::ffff:a.b.c.d) and IPv4-compatible: unwrap and re-check,
            // otherwise ::ffff:127.0.0.1 walks straight past every test above.
            boolean firstTenZero = true;
            for (int i = 0; i < 10; i++) {
                if (b[i] != 0) { firstTenZero = false; break; }
            }
            if (firstTenZero) {
                boolean mapped = (b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF;
                boolean compat = b[10] == 0 && b[11] == 0;
                if (mapped || compat) {
                    try {
                        return isPrivate(InetAddress.getByAddress(
                                new byte[]{b[12], b[13], b[14], b[15]}));
                    } catch (Exception e) {
                        return true;   // cannot verify, so refuse
                    }
                }
            }
            return false;
        }

        // Unknown address family — refuse rather than guess.
        return true;
    }

    /**
     * GET the URL, following redirects by hand and re-checking every hop.
     *
     * @return the page, or null for any refusal, error, timeout, non-200,
     *         non-HTML body, or oversize response. Callers treat a missing page
     *         as "no source", never as an error worth surfacing.
     */
    public Page fetch(String url) {
        String current = url;

        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            Refusal refusal = check(current);
            if (refusal != Refusal.OK) {
                log.warn("[web] refused {} — {}", current, refusal);
                return null;
            }

            HttpResponse<InputStream> res;
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(current))
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "text/html,application/xhtml+xml")
                        .header("Accept-Language", "en")
                        .timeout(REQUEST_TIMEOUT)
                        .GET()
                        .build();
                res = http().send(req, HttpResponse.BodyHandlers.ofInputStream());
            } catch (Exception e) {
                log.debug("[web] fetch failed for {}: {}", current, e.getMessage());
                return null;
            }

            int status = res.statusCode();
            if (status >= 300 && status < 400) {
                String location = res.headers().firstValue("location").orElse(null);
                closeQuietly(res.body());
                if (location == null) return null;
                // Resolved against the current URL so relative redirects work,
                // then re-checked at the top of the loop.
                current = URI.create(current).resolve(location).toString();
                continue;
            }

            if (status != 200) {
                closeQuietly(res.body());
                log.debug("[web] {} returned {}", current, status);
                return null;
            }

            String contentType = res.headers().firstValue("content-type").orElse("");
            if (!contentType.toLowerCase(Locale.ROOT).contains("html")
                    && !contentType.toLowerCase(Locale.ROOT).contains("text/plain")) {
                closeQuietly(res.body());
                log.debug("[web] {} is {} — not readable text", current, contentType);
                return null;
            }

            String body = readCapped(res.body());
            if (body == null) return null;
            return new Page(current, contentType, body);
        }

        log.debug("[web] too many redirects from {}", url);
        return null;
    }

    /**
     * Reads at most MAX_BYTES.
     *
     * Content-Length is not trusted for this — a hostile or broken server can
     * understate it, and the point is to bound what actually lands in memory.
     */
    private static String readCapped(InputStream in) {
        try (in) {
            byte[] buf = new byte[8192];
            var out = new java.io.ByteArrayOutputStream();
            int total = 0, n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_BYTES) {
                    out.write(buf, 0, n - (total - MAX_BYTES));
                    break;
                }
                out.write(buf, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static void closeQuietly(InputStream in) {
        try {
            if (in != null) in.close();
        } catch (Exception ignored) {
            // Draining a redirect body is best-effort.
        }
    }
}
