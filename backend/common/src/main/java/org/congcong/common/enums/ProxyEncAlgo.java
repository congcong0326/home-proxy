package org.congcong.common.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

public enum ProxyEncAlgo {
    aes_256_gcm("aes_256_gcm", null),
    aes_128_gcm("aes_128_gcm", null),
    chacha20_ietf_poly1305("chacha20_ietf_poly1305", null),
    blake3_2022_aes_128_gcm("2022-blake3-aes-128-gcm", 16),
    blake3_2022_aes_256_gcm("2022-blake3-aes-256-gcm", 32),
    blake3_2022_chacha20_poly1305("2022-blake3-chacha20-poly1305", 32);

    private final String value;
    private final Integer pskLength;

    ProxyEncAlgo(String value, Integer pskLength) {
        this.value = value;
        this.pskLength = pskLength;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    public boolean isShadowSocks2022() {
        return pskLength != null;
    }

    public Integer getPskLength() {
        return pskLength;
    }

    @JsonCreator
    public static ProxyEncAlgo fromValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Arrays.stream(values())
                .filter(item -> item.value.equalsIgnoreCase(value) || item.name().equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported proxy encryption algorithm: " + value));
    }
}
