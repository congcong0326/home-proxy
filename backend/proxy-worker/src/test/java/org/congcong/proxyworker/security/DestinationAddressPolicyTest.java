package org.congcong.proxyworker.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;

class DestinationAddressPolicyTest {

    @Test
    void allowsPublicIpv4AndIpv6() throws Exception {
        assertTrue(DestinationAddressPolicy.isPubliclyRoutable(InetAddress.getByName("8.8.8.8")));
        assertTrue(DestinationAddressPolicy.isPubliclyRoutable(InetAddress.getByName("2606:4700:4700::1111")));
    }

    @Test
    void blocksIpv4PrivateSpecialAndReservedRanges() throws Exception {
        for (String address : new String[] {
                "0.1.2.3", "10.0.0.1", "100.64.0.1", "127.0.0.1", "169.254.1.1",
                "172.16.0.1", "192.0.2.1", "192.168.1.1", "198.18.0.1",
                "198.51.100.1", "203.0.113.1", "224.0.0.1", "240.0.0.1"
        }) {
            assertFalse(DestinationAddressPolicy.isPubliclyRoutable(InetAddress.getByName(address)), address);
        }
    }

    @Test
    void blocksIpv6PrivateSpecialAndMappedIpv4Ranges() throws Exception {
        for (String address : new String[] {
                "::", "::1", "fc00::1", "fe80::1", "fec0::1", "2001:db8::1", "ff02::1",
                "64:ff9b:1::1", "100::1", "2001:2::1", "2001:10::1",
                "::ffff:192.168.1.1", "::192.168.1.1"
        }) {
            assertFalse(DestinationAddressPolicy.isPubliclyRoutable(InetAddress.getByName(address)), address);
        }
    }

    @Test
    void parsesBracketedLiteralsWithoutTreatingNamesAsLiterals() {
        assertTrue(DestinationAddressPolicy.parseLiteral("[2001:4860:4860::8888]").isPresent());
        assertTrue(DestinationAddressPolicy.parseLiteral("203.0.114.1").isPresent());
        assertTrue(DestinationAddressPolicy.parseLiteral("not-an-ip.example").isEmpty());
    }
}
