package com.praxis.prax.web;

import com.praxis.prax.web.SafeFetcher.Refusal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The security boundary of the web feature.
 *
 * Everything here uses literal IP addresses, so no test needs DNS or a network.
 *
 * MUTATION TEST THIS FILE. Delete the loopback branch in isPrivate and confirm
 * tests go red before trusting it — a security check nobody has watched fail is
 * a security check nobody knows works.
 */
@DisplayName("SafeFetcher SSRF Guard Tests")
class SafeFetcherTest {

    @Nested
    @DisplayName("Scheme Tests")
    class SchemeTests {

        @ParameterizedTest
        @ValueSource(strings = {
                "file:///etc/passwd",
                "file://C:/Windows/System32/drivers/etc/hosts",
                "ftp://example.com/x",
                "gopher://example.com/",
                "jar:file:///tmp/x.jar!/",
                "ldap://example.com/",
                "netdoc:///etc/passwd",
                "data:text/plain,hello",
        })
        @DisplayName("Should refuse every scheme that is not http or https")
        void shouldRefuseNonHttpSchemes(String url) {
            // An allow-list, so a scheme added to the JDK later is refused by
            // default rather than silently permitted.
            assertThat(SafeFetcher.check(url)).isEqualTo(Refusal.BAD_SCHEME);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "data:text/html,<script>alert(1)</script>",
                "http://exa mple.com/",
                "http://[::ffff:127.0.0.1/",
        })
        @DisplayName("Should refuse malformed dangerous URLs by some route")
        void shouldRefuseMalformedDangerousUrls(String url) {
            // These are rejected as BAD_SYNTAX rather than BAD_SCHEME, because
            // URI parsing fails on the illegal characters before the scheme is
            // ever considered. Which refusal fires does not matter; that one
            // fires does.
            assertThat(SafeFetcher.check(url)).isNotEqualTo(Refusal.OK);
        }

        @Test
        @DisplayName("Should refuse a URL with no scheme at all")
        void shouldRefuseSchemeless() {
            assertThat(SafeFetcher.check("//evil.example/x")).isEqualTo(Refusal.BAD_SCHEME);
        }
    }

    @Nested
    @DisplayName("Private Address Tests")
    class PrivateAddressTests {

        @ParameterizedTest
        @ValueSource(strings = {
                "http://127.0.0.1/",
                "http://127.0.0.1:8086/api/games",
                "http://127.1.2.3/",
                "http://localhost:11434/api/chat",
                "http://0.0.0.0/",
                "http://10.0.0.5/",
                "http://172.16.4.9/",
                "http://192.168.1.1/",
                "http://169.254.169.254/latest/meta-data/",
                "http://100.64.0.1/",
                "http://100.127.255.254/",
                "http://255.255.255.255/",
                "http://240.0.0.1/",
                "http://[::1]/",
                "http://[fc00::1]/",
                "http://[fd12:3456:789a::1]/",
                "http://[::ffff:127.0.0.1]/",
        })
        @DisplayName("Should refuse addresses inside the machine or the local network")
        void shouldRefusePrivateAddresses(String url) {
            assertThat(SafeFetcher.check(url)).isEqualTo(Refusal.PRIVATE_ADDRESS);
        }

        @Test
        @DisplayName("Should refuse the services this app itself runs")
        void shouldRefuseOwnServices() {
            // Named explicitly because these are the actual targets: Ollama has no
            // authentication and the backend's own API would happily answer.
            assertThat(SafeFetcher.check("http://localhost:8086/api/play/history"))
                    .isEqualTo(Refusal.PRIVATE_ADDRESS);
            assertThat(SafeFetcher.check("http://localhost:11434/api/tags"))
                    .isEqualTo(Refusal.PRIVATE_ADDRESS);
            assertThat(SafeFetcher.check("http://127.0.0.1:5432/"))
                    .isEqualTo(Refusal.PRIVATE_ADDRESS);
        }

        @Test
        @DisplayName("Should unwrap IPv4-mapped IPv6 before judging it")
        void shouldUnwrapMappedIpv6() throws Exception {
            // ::ffff:127.0.0.1 is loopback wearing an IPv6 hat. Without the
            // unwrap it passes every predicate above.
            byte[] mappedLoopback = new byte[16];
            mappedLoopback[10] = (byte) 0xFF;
            mappedLoopback[11] = (byte) 0xFF;
            mappedLoopback[12] = 127;
            mappedLoopback[15] = 1;
            assertThat(SafeFetcher.isPrivate(InetAddress.getByAddress(mappedLoopback))).isTrue();

            byte[] mappedPrivate = mappedLoopback.clone();
            mappedPrivate[12] = 10; mappedPrivate[13] = 0; mappedPrivate[14] = 0; mappedPrivate[15] = 5;
            assertThat(SafeFetcher.isPrivate(InetAddress.getByAddress(mappedPrivate))).isTrue();
        }
    }

    @Nested
    @DisplayName("Allowed Address Tests")
    class AllowedAddressTests {

        @ParameterizedTest
        @ValueSource(strings = {
                "http://8.8.8.8/",
                "https://1.1.1.1/",
                "https://93.184.216.34/",
                "https://[2606:2800:220:1:248:1893:25c8:1946]/",
        })
        @DisplayName("Should allow public addresses")
        void shouldAllowPublicAddresses(String url) {
            // The guard must not be so broad that it blocks the actual feature.
            assertThat(SafeFetcher.check(url)).isEqualTo(Refusal.OK);
        }

        @Test
        @DisplayName("Should allow a public address on a non-standard port")
        void shouldAllowPublicNonStandardPort() {
            assertThat(SafeFetcher.check("https://8.8.8.8:8443/search")).isEqualTo(Refusal.OK);
        }
    }

    @Nested
    @DisplayName("Malformed Input Tests")
    class MalformedInputTests {

        @Test
        @DisplayName("Should refuse a URL with no host")
        void shouldRefuseHostless() {
            assertThat(SafeFetcher.check("http:///path")).isEqualTo(Refusal.BAD_SYNTAX);
        }

        @Test
        @DisplayName("Should refuse a host that does not resolve")
        void shouldRefuseUnresolvable() {
            // .invalid is reserved by RFC 2606 and can never resolve.
            assertThat(SafeFetcher.check("http://nothing.invalid/"))
                    .isEqualTo(Refusal.UNRESOLVABLE);
        }

        @Test
        @DisplayName("Should not throw on rubbish input")
        void shouldNotThrowOnRubbish() {
            // check() returns a refusal for everything; it never propagates.
            assertThat(SafeFetcher.check("http://[not-an-address]/")).isNotEqualTo(Refusal.OK);
            assertThat(SafeFetcher.check("   ")).isNotEqualTo(Refusal.OK);
        }
    }

    @Nested
    @DisplayName("Fetch Behaviour Tests")
    class FetchBehaviourTests {

        @Test
        @DisplayName("Should return null rather than throwing for a refused URL")
        void shouldReturnNullForRefusedUrl() {
            // A blocked URL is an ordinary outcome. Throwing here would put a
            // stack trace into the agent loop for something entirely expected.
            assertThat(new SafeFetcher().fetch("http://127.0.0.1:8086/api/games")).isNull();
            assertThat(new SafeFetcher().fetch("file:///etc/passwd")).isNull();
        }
    }
}
