package org.congcong.controlmanager.service.mail.inspection;

import lombok.RequiredArgsConstructor;
import org.congcong.common.telemetry.CredentialAuthLogV1;
import org.congcong.controlmanager.clickhouse.ClickHouseJdbcClient;
import org.congcong.controlmanager.telemetry.CredentialGeoAnalysis;
import org.congcong.controlmanager.telemetry.TelemetryStore;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Pattern;

import static org.congcong.controlmanager.service.mail.inspection.InspectionFormats.*;

@Service
@RequiredArgsConstructor
public class AuthLocationInspectionService {
    private final ClickHouseJdbcClient client;
    private final TelemetryStore store;
    private final CredentialGeoAnalysis geo;
    private final InspectionProperties properties;

    public InspectionSection inspect(List<String> workers, Instant from, Instant to, ZoneId zone) {
        String title = "认证成功位置与变化";
        if (workers.isEmpty()) return new InspectionSection(title, List.of(), List.of("没有启用的网关。"));
        store.requireQueryAvailable();
        var currentRows = query(workers, from, to, zone);
        var historyRows = query(workers, from.minus(properties.getAuthBaselineDays(), ChronoUnit.DAYS), from, zone);
        Map<String, Location> locations = new HashMap<>();
        Map<String, Account> accounts = new TreeMap<>();
        for (var row : historyRows) {
            var account = account(accounts, row, false);
            Location location = locate(locations, Objects.toString(row.get("ip"), ""));
            if (location.resolved()) {
                account.history.add(location);
                var dates = Pattern.compile("\\d{4}-\\d{2}-\\d{2}").matcher(Objects.toString(row.get("days"), ""));
                while (dates.find()) account.days.add(dates.group());
            }
        }
        BigInteger total = BigInteger.ZERO, privateCount = BigInteger.ZERO, unknownCount = BigInteger.ZERO;
        for (var row : currentRows) {
            var account = account(accounts, row, true);
            String ip = Objects.toString(row.get("ip"), "");
            Location location = locate(locations, ip);
            BigInteger count = integer(row.get("records"));
            total = total.add(count);
            if ("PRIVATE".equals(location.status)) privateCount = privateCount.add(count);
            else if (!location.resolved()) unknownCount = unknownCount.add(count);
            var occurrence = account.current.computeIfAbsent(location, ignored -> new Occurrence());
            occurrence.count = occurrence.count.add(count);
            occurrence.sources.add(ip.isBlank() ? "来源 IP 未提供" : ip);
            Instant first = instant(row.get("first_at")), last = instant(row.get("last_at"));
            if (occurrence.first == null || first != null && first.isBefore(occurrence.first)) occurrence.first = first;
            if (occurrence.last == null || last != null && last.isAfter(occurrence.last)) occurrence.last = last;
        }
        var items = new ArrayList<InspectionSection.Item>();
        items.add(InspectionSection.item("成功认证总览", "成功认证次数", total.toString(),
                "内网来源次数", privateCount.toString(), "位置未知次数", unknownCount.toString(),
                "历史参考窗口", time(from.minus(properties.getAuthBaselineDays(), ChronoUnit.DAYS), zone) + " 至 " + time(from, zone)));
        for (var account : accounts.values()) {
            if (account.current.isEmpty()) continue;
            boolean sufficient = !account.unknownIdentity && account.days.size() >= properties.getAuthMinimumHistoryDays()
                    && !account.history.isEmpty();
            var publicCountries = new HashSet<String>();
            var publicRegions = new HashSet<String>();
            for (var location : account.current.keySet()) {
                if (location.resolved()) {
                    publicCountries.add(location.countryKey());
                    if (!location.region.isBlank()) publicRegions.add(location.regionKey());
                }
            }
            String history = account.history.stream().map(Location::label).sorted().distinct()
                    .reduce((a, b) -> a + "；" + b).orElse("没有可定位的历史成功记录");
            items.add(InspectionSection.item("账号 " + account.name,
                    "历史成功位置", history,
                    "基线状态", (sufficient ? "可参考" : "基线不足") + "；历史可定位认证日期 " + account.days.size()
                            + " 天，要求至少 " + properties.getAuthMinimumHistoryDays() + " 天",
                    "本窗口跨地区情况", publicCountries.size() > 1 || publicRegions.size() > 1
                            ? "出现多个国家 / 地区（国家 " + publicCountries.size() + "，已知地区 " + publicRegions.size() + "）"
                            : publicCountries.isEmpty() ? "没有可定位的公网来源，无法判断"
                            : "未观察到多个已定位国家 / 地区"));
            for (var entry : account.current.entrySet()) {
                Location location = entry.getKey();
                Occurrence occurrence = entry.getValue();
                items.add(InspectionSection.item(account.name + " / " + location.label(),
                        "成功次数", occurrence.count.toString(), "来源 IP", String.join("、", occurrence.sources),
                        "首次 / 最近认证", time(occurrence.first, zone) + " / " + time(occurrence.last, zone),
                        "位置变化", compare(location, account.history, sufficient),
                        "定位状态 / 数据库版本", location.status + " / " + text(location.version)));
            }
        }
        var notes = new ArrayList<String>(List.of(
                "仅比较 SUCCESS；优先按用户 ID 识别同一账号，否则使用已验证账号。匿名 / 设备 IP 识别不属于凭据认证。",
                "历史窗口不包含本次巡检；当前与历史 IP 使用本次查询的同一 GeoIP 数据库定位，不使用旧分析结果推断变化。",
                "国家 / 地区新增重点展示，城市变化仅供参考；IP 改变不等于位置偏移，也不能据位置变化认定账号被盗。",
                "内网来源和未知位置不参与偏移判断；历史成功日期不能证明采集连续或基线完整。"));
        if (total.signum() == 0) notes.add("本窗口没有成功认证记录，无法判断当前认证来源是否发生变化。");
        return new InspectionSection(title, items, notes);
    }

    private List<Map<String, Object>> query(List<String> workers, Instant from, Instant to, ZoneId zone) {
        var params = new ArrayList<Object>();
        params.add(zone.getId()); params.addAll(workers); params.add(Timestamp.from(from)); params.add(Timestamp.from(to));
        return client.query("""
                SELECT coalesce(toString(user_id), '') AS account_id,
                  coalesce(nullIf(verified_username, ''), nullIf(username, ''), '') AS account,
                  coalesce(client_ip, '') AS ip, toString(count()) AS records,
                  min(occurred_at) AS first_at, max(occurred_at) AS last_at,
                  toString(groupUniqArray(toString(toDate(occurred_at, ?)))) AS days
                FROM telemetry.credential_auth_log_v1 FINAL
                WHERE auth_outcome = 'SUCCESS' AND worker_id IN (
                """ + String.join(",", Collections.nCopies(workers.size(), "?"))
                + ") AND occurred_at >= ? AND occurred_at < ? GROUP BY account_id, account, ip ORDER BY account_id, account, ip",
                params.toArray());
    }

    private Location locate(Map<String, Location> cache, String ip) {
        return cache.computeIfAbsent(ip, key -> {
            var fact = new CredentialAuthLogV1(); fact.setClientIp(key);
            var value = geo.analyze(fact);
            return new Location(normalized(value.getSourceCountryCode()), normalized(value.getSourceCountry()),
                    normalized(value.getSourceRegion()), normalized(value.getSourceCity()),
                    normalized(value.getGeoLookupStatus()), value.getGeoDatabaseVersion());
        });
    }

    private static Account account(Map<String, Account> accounts, Map<String, Object> row, boolean current) {
        String id = Objects.toString(row.get("account_id"), ""), name = Objects.toString(row.get("account"), "").strip();
        boolean unknown = id.isBlank() && name.isBlank();
        String key = !id.isBlank() ? "id:" + id : !name.isBlank() ? "name:" + name : "unknown:" + row.get("ip");
        String display = (!name.isBlank() ? name : unknown ? "未知账号" : "用户") + (!id.isBlank() ? "（ID " + id + "）" : "");
        Account account = accounts.computeIfAbsent(key, ignored -> new Account(display, unknown));
        if (current && !name.isBlank()) account.name = display;
        return account;
    }

    static String compare(Location current, Set<Location> history, boolean sufficient) {
        if ("PRIVATE".equals(current.status)) return "内网来源，不参与地理位置偏移判断";
        if (!current.resolved()) return "位置未知，无法判断偏移";
        if (!sufficient) return "基线不足，暂不判断偏移";
        boolean knownCountry = history.stream().anyMatch(old -> old.countryKey().equals(current.countryKey()));
        if (!knownCountry) return "新增成功认证国家 / 地区，请结合实际使用情况核对";
        if (current.region.isBlank()) return "国家未见新增；地区未知，无法比较地区";
        var countryHistory = history.stream().filter(old -> old.countryKey().equals(current.countryKey()) && !old.region.isBlank()).toList();
        if (countryHistory.isEmpty()) return "国家未见新增；历史地区信息不足";
        var regionHistory = countryHistory.stream().filter(old -> old.region.equals(current.region)).toList();
        if (regionHistory.isEmpty()) return "新增成功认证地区，请结合实际使用情况核对";
        if (current.city.isBlank()) return "国家 / 地区未见新增；城市未知";
        var cityHistory = regionHistory.stream().filter(old -> !old.city.isBlank()).toList();
        if (cityHistory.isEmpty()) return "国家 / 地区未见新增；历史城市信息不足";
        return cityHistory.stream().anyMatch(old -> old.city.equals(current.city)) ? "未观察到新增位置"
                : "城市变化（仅供参考）";
    }

    private static String normalized(String value) { return value == null ? "" : value.strip(); }

    record Location(String code, String country, String region, String city, String status, String version) {
        boolean resolved() { return "RESOLVED".equals(status) && (!code.isBlank() || !country.isBlank()); }
        String countryKey() { return code.isBlank() ? country : code; }
        String regionKey() { return countryKey() + "/" + region; }
        String label() {
            if ("PRIVATE".equals(status)) return "内网来源";
            if (!resolved()) return "位置未知";
            return (country.isBlank() ? countryKey() : country) + " / " + (region.isBlank() ? "地区未知" : region)
                    + " / " + (city.isBlank() ? "城市未知" : city);
        }
    }

    private static class Account {
        String name;
        final boolean unknownIdentity;
        final Set<Location> history = new HashSet<>();
        final Set<String> days = new HashSet<>();
        final Map<Location, Occurrence> current = new LinkedHashMap<>();
        Account(String name, boolean unknownIdentity) { this.name = name; this.unknownIdentity = unknownIdentity; }
    }

    private static class Occurrence {
        BigInteger count = BigInteger.ZERO;
        final Set<String> sources = new TreeSet<>();
        Instant first;
        Instant last;
    }
}
