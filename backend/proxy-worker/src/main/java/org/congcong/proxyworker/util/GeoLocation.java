package org.congcong.proxyworker.util;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 地理位置结果：国家与城市（可能为空）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GeoLocation {
    private String country;
    private String city;
    private String ip;
}