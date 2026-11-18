package dev.skeletonkey.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.skeletonkey.web.ClientIp;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/** Property 17: X-Real-IP is used iff the peer is a trusted proxy and the header is a single IP literal. */
class ClientIpPropertyTest {

    private final TrustedProxies defaults = TrustedProxies.parse(TrustedProxies.DEFAULT);

    @Provide
    Arbitrary<String> ipv4() {
        Arbitrary<Integer> octet = Arbitraries.integers().between(0, 255);
        return Combinators.combine(octet, octet, octet, octet).as((a, b, c, d) -> a + "." + b + "." + c + "." + d);
    }

    @Provide
    Arbitrary<String> headers() {
        return Arbitraries.oneOf(ipv4(), Arbitraries.of("", "evil", "1.2.3.4, 5.6.7.8", "999.1.1.1", "::1",
                "2001:db8::7", "localhost", "example.org"), Arbitraries.strings().ofMaxLength(20)).injectNull(0.1);
    }

    private static boolean inDefaults(String ip) {
        String[] p = ip.split("\\.");
        int a = Integer.parseInt(p[0]);
        int b = Integer.parseInt(p[1]);
        return a == 127 || a == 10 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168);
    }

    @Property(tries = 1000)
    void headerIsBelievedOnlyFromTrustedPeers(@ForAll("ipv4") String peer, @ForAll("headers") String header) {
        String resolved = ClientIp.resolve(peer, header, defaults);
        boolean headerIsIp = header != null && !header.contains(",") && TrustedProxies.literal(header) != null;
        if (inDefaults(peer) && headerIsIp) {
            assertThat(resolved).isEqualTo(header.trim());
        } else {
            assertThat(resolved).isEqualTo(peer);
        }
    }

    @Example
    void parsesCidrsAndSingleAddresses() {
        TrustedProxies t = TrustedProxies.parse("203.0.113.0/24, 2001:db8::/32 ,198.51.100.7");
        assertThat(t.isTrusted("203.0.113.200")).isTrue();
        assertThat(t.isTrusted("203.0.114.1")).isFalse();
        assertThat(t.isTrusted("2001:db8:1::5")).isTrue();
        assertThat(t.isTrusted("2001:db9::5")).isFalse();
        assertThat(t.isTrusted("198.51.100.7")).isTrue();
        assertThat(t.isTrusted("198.51.100.8")).isFalse();
        assertThat(defaults.isTrusted("::1")).isTrue();
        assertThat(defaults.isTrusted("172.32.0.1")).isFalse();
        assertThat(defaults.isTrusted("not-an-ip")).isFalse();
        assertThat(TrustedProxies.parse("").isTrusted("127.0.0.1")).isFalse();
    }

    @Example
    void invalidEntriesNameTheProperty() {
        for (String bad : new String[] {"10.0.0.0/33", "10.0.0/8", "proxy.local", "1.2.3.4/x", "::1/129"}) {
            assertThatThrownBy(() -> TrustedProxies.parse(bad)).as(bad)
                    .hasMessageContaining(TrustedProxies.PROPERTY);
        }
    }
}
