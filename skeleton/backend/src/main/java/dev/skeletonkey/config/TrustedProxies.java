package dev.skeletonkey.config;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The peers allowed to tell us the real client address ({@code skeletonkey.trusted-proxies}): a comma-separated list
 * of IPv4/IPv6 addresses and CIDR ranges. Parsing never does a DNS lookup.
 */
public final class TrustedProxies {

    public static final String PROPERTY = "skeletonkey.trusted-proxies";
    public static final String DEFAULT = "127.0.0.0/8,::1,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16";

    private static final Pattern IPV4 = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");
    private static final Pattern IPV6 = Pattern.compile("^[0-9a-fA-F:.]{2,45}$");

    private record Range(byte[] network, int prefix) {
        boolean contains(byte[] addr) {
            if (addr.length != network.length) {
                return false;
            }
            int full = prefix / 8;
            for (int i = 0; i < full; i++) {
                if (addr[i] != network[i]) {
                    return false;
                }
            }
            int rest = prefix % 8;
            if (rest == 0) {
                return true;
            }
            int mask = (0xFF << (8 - rest)) & 0xFF;
            return (addr[full] & mask) == (network[full] & mask);
        }
    }

    private final List<Range> ranges;

    private TrustedProxies(List<Range> ranges) {
        this.ranges = List.copyOf(ranges);
    }

    /** @throws IllegalArgumentException naming the property for any invalid entry */
    public static TrustedProxies parse(String spec) {
        List<Range> out = new ArrayList<>();
        if (spec == null || spec.isBlank()) {
            return new TrustedProxies(out);
        }
        for (String raw : spec.split(",")) {
            String entry = raw.trim();
            if (entry.isEmpty()) {
                continue;
            }
            String addr = entry;
            Integer prefix = null;
            int slash = entry.indexOf('/');
            if (slash >= 0) {
                addr = entry.substring(0, slash);
                try {
                    prefix = Integer.parseInt(entry.substring(slash + 1));
                } catch (NumberFormatException e) {
                    throw invalid(entry);
                }
            }
            byte[] bytes = literal(addr);
            if (bytes == null) {
                throw invalid(entry);
            }
            int max = bytes.length * 8;
            int p = prefix == null ? max : prefix;
            if (p < 0 || p > max) {
                throw invalid(entry);
            }
            out.add(new Range(bytes, p));
        }
        return new TrustedProxies(out);
    }

    public boolean isTrusted(String address) {
        byte[] bytes = literal(address);
        if (bytes == null) {
            return false;
        }
        for (Range r : ranges) {
            if (r.contains(bytes)) {
                return true;
            }
        }
        return false;
    }

    /** The address bytes of an IPv4 or IPv6 literal, or null when the text is not one. Never resolves names. */
    public static byte[] literal(String text) {
        if (text == null) {
            return null;
        }
        String s = text.trim();
        if (s.startsWith("[") && s.endsWith("]")) {
            s = s.substring(1, s.length() - 1);
        }
        var m = IPV4.matcher(s);
        if (m.matches()) {
            byte[] b = new byte[4];
            for (int i = 0; i < 4; i++) {
                int v = Integer.parseInt(m.group(i + 1));
                if (v > 255) {
                    return null;
                }
                b[i] = (byte) v;
            }
            return b;
        }
        if (s.contains(":") && IPV6.matcher(s).matches()) {
            try {
                // With a ':' and only hex/':'/'.' characters, getByName parses a literal and does no lookup.
                byte[] b = InetAddress.getByName(s).getAddress();
                if (b.length == 16 && isV4Mapped(b)) {
                    return new byte[] {b[12], b[13], b[14], b[15]};
                }
                return b;
            } catch (UnknownHostException | IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }

    private static boolean isV4Mapped(byte[] b) {
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return b[10] == (byte) 0xFF && b[11] == (byte) 0xFF;
    }

    private static IllegalArgumentException invalid(String entry) {
        return new IllegalArgumentException(PROPERTY + " has an invalid entry '" + entry
                + "'; use IP addresses or CIDR ranges such as 10.0.0.0/8");
    }
}
