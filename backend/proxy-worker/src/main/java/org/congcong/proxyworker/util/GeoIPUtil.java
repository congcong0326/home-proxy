package org.congcong.proxyworker.util;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.model.CityResponse;
import lombok.extern.slf4j.Slf4j;
import org.congcong.proxyworker.server.ProxyContext;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * IP/域名地理位置解析工具。
 * 支持：
 * - 通过域名或IP解析最终IP地址
 * - 使用 GeoLite2-City.mmdb 解析国家与城市
 * - 可选启用 Guava 缓存提升性能
 */
@Slf4j
public class GeoIPUtil implements AutoCloseable {



    private static class Holder {
        private static final GeoIPUtil INSTANCE = new GeoIPUtil(true, 2000);
    }

    public static GeoIPUtil getInstance() {
        return GeoIPUtil.Holder.INSTANCE;
    }

    private final boolean cacheEnabled;
    private final Cache<String, GeoLocation> cache;
    private DatabaseReader dbReader;

    /**
     * 构造函数。
     * @param cacheEnabled 是否启用缓存
     * @param maximumCacheSize 缓存最大条目数
     */
    private GeoIPUtil(boolean cacheEnabled,
                     long maximumCacheSize) {
        this.cacheEnabled = cacheEnabled;
        this.cache = cacheEnabled ? CacheBuilder.newBuilder()
                .maximumSize(Math.max(1000, maximumCacheSize))
                .build() : null;

        try {
            String jarDir = System.getProperty("user.dir");  // 获取当前工作目录（JAR所在的目录）
            File workDir = new File(jarDir);
            File[] files = workDir.listFiles();
            if (files != null) {
                File mmdb = null;
                for (File file : files) {
                    if (file.getName().contains("mmdb")) {
                        mmdb = file;
                        break;
                    }
                }
                if (mmdb != null) {
                    log.info("load GeoLite2-City.mmdb");
                    dbReader = new DatabaseReader.Builder(mmdb).build();
                } else {
                    log.info("not find mmdb");
                }
            }
        } catch (IOException e) {
            log.warn("load GeoLite2-City.mmdb", e);
        }
    }



    /**
     * 根据域名或IP获取地理位置。
     */
    public Optional<GeoLocation> lookup(String hostOrIp) {
        if (hostOrIp == null || hostOrIp.isBlank()) {
            return Optional.empty();
        }

        String ip = resolveToIp(hostOrIp);
        if (ip == null) {
            return Optional.empty();
        }

        if (dbReader == null) {
            return Optional.empty();
        }

        if (cacheEnabled && cache != null) {
            GeoLocation cached = cache.getIfPresent(ip);
            if (cached != null) {
                return Optional.of(cached);
            }
        }

        try {
            InetAddress inetAddress = InetAddress.getByName(ip);
            CityResponse response = dbReader.city(inetAddress);
            String country = response.getCountry() != null ? response.getCountry().getNames().get("zh-CN")
                    : null;
            if (country == null && response.getCountry() != null) {
                country = response.getCountry().getName();
            }
            String city = response.getCity() != null ? response.getCity().getNames().get("zh-CN") : null;
            if (city == null && response.getCity() != null) {
                city = response.getCity().getName();
            }
            GeoLocation result = new GeoLocation(country, city, ip);
            if (cacheEnabled && cache != null) {
                cache.put(ip, result);
            }
            return Optional.of(result);
        } catch (Exception e) {
            log.debug("GeoIP2 解析失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 将域名或IP解析为IP地址（IPv4/IPv6皆可）。
     */
    public String resolveToIp(String hostOrIp) {
        try {
            InetAddress address = InetAddress.getByName(hostOrIp);
            return address.getHostAddress();
        } catch (UnknownHostException e) {
            log.debug("主机解析失败: {}", hostOrIp);
            return null;
        }
    }



    @Override
    public void close() throws Exception {
        if (dbReader != null) {
            try {
                dbReader.close();
            } catch (IOException ignored) { }
        }
    }
}