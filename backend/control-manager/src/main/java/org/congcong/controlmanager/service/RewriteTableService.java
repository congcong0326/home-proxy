package org.congcong.controlmanager.service;

import lombok.RequiredArgsConstructor;
import org.congcong.controlmanager.dto.rewrite.RewriteEntryDTO;
import org.congcong.controlmanager.dto.rewrite.RewriteTableImportRequest;
import org.congcong.controlmanager.dto.rewrite.RewriteTableRequest;
import org.congcong.controlmanager.dto.rewrite.RewriteTableView;
import org.congcong.controlmanager.entity.RewriteEntry;
import org.congcong.controlmanager.entity.RewriteTable;
import org.congcong.controlmanager.entity.Route;
import org.congcong.controlmanager.repository.RewriteEntryRepository;
import org.congcong.controlmanager.repository.RewriteTableRepository;
import org.congcong.controlmanager.repository.RouteRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 改写表管理。条目按域名模式与已有条目对齐后更新，保持条目 ID 稳定，从而保证展开出的合成路由 ID 跨保存不变。
 */
@Service
@RequiredArgsConstructor
public class RewriteTableService {
    /** {@code DomainMatcher} 支持的写法：a.com、.a.com、*.a.com；不允许单独的 *。 */
    private static final Pattern DOMAIN_PATTERN = Pattern.compile("^(\\*\\.|\\.)?[a-z0-9_]([a-z0-9_-]*[a-z0-9_])?(\\.[a-z0-9_]([a-z0-9_-]*[a-z0-9_])?)*$");

    private final RewriteTableRepository rewriteTableRepository;
    private final RewriteEntryRepository rewriteEntryRepository;
    private final RouteRepository routeRepository;

    public List<RewriteTableView> getTables() {
        Map<Long, Long> routeCounts = routeRepository.findAll().stream()
                .filter(route -> route.getRewriteTableId() != null)
                .collect(Collectors.groupingBy(Route::getRewriteTableId, Collectors.counting()));
        return rewriteTableRepository.findAllByOrderByIdAsc().stream()
                .map(table -> toView(table, routeCounts.getOrDefault(table.getId(), 0L)))
                .toList();
    }

    public RewriteTableView getTable(Long id) {
        return toView(require(id), routeRepository.findByRewriteTableId(id).size());
    }

    public RewriteTable require(Long id) {
        return rewriteTableRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "改写表不存在: " + id));
    }

    public long countEntries(Long tableId) {
        return rewriteEntryRepository.countByTableId(tableId);
    }

    @Transactional
    @CacheEvict(value = {"aggregateConfig"}, allEntries = true)
    public RewriteTableView createTable(RewriteTableRequest request) {
        if (rewriteTableRepository.existsByName(request.getName().trim())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "改写表名称已存在");
        }
        List<RewriteEntryDTO> entries = normalize(request.getEntries());
        RewriteTable table = new RewriteTable();
        table.setName(request.getName().trim());
        table.setNotes(request.getNotes());
        table = rewriteTableRepository.save(table);
        replaceEntries(table.getId(), entries);
        return toView(table, 0);
    }

    @Transactional
    @CacheEvict(value = {"aggregateConfig"}, allEntries = true)
    public RewriteTableView updateTable(Long id, RewriteTableRequest request) {
        RewriteTable table = require(id);
        if (rewriteTableRepository.existsByNameAndIdNot(request.getName().trim(), id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "改写表名称已存在");
        }
        List<RewriteEntryDTO> entries = normalize(request.getEntries());
        table.setName(request.getName().trim());
        table.setNotes(request.getNotes());
        table = rewriteTableRepository.save(table);
        replaceEntries(id, entries);
        return toView(table, routeRepository.findByRewriteTableId(id).size());
    }

    @Transactional
    @CacheEvict(value = {"aggregateConfig"}, allEntries = true)
    public RewriteTableView importEntries(Long id, RewriteTableImportRequest request) {
        RewriteTable table = require(id);
        List<RewriteEntryDTO> imported = parse(request.getText());
        List<RewriteEntryDTO> merged;
        if (request.isReplace()) {
            merged = imported;
        } else {
            Map<String, RewriteEntryDTO> byPattern = new LinkedHashMap<>();
            entriesOf(id).forEach(entry -> byPattern.put(entry.getDomainPattern(), entry));
            for (RewriteEntryDTO entry : imported) {
                byPattern.put(entry.getDomainPattern().trim().toLowerCase(Locale.ROOT), entry);
            }
            merged = new ArrayList<>(byPattern.values());
        }
        replaceEntries(id, normalize(merged));
        return toView(table, routeRepository.findByRewriteTableId(id).size());
    }

    public String exportEntries(Long id) {
        require(id);
        return entriesOf(id).stream()
                .map(entry -> {
                    String line = entry.getDomainPattern() + " " + formatTarget(entry.getTargetHost(), entry.getTargetPort());
                    if (!Boolean.TRUE.equals(entry.getEnabled())) line = "# " + line;
                    return entry.getNotes() == null || entry.getNotes().isBlank() ? line : line + " " + entry.getNotes();
                })
                .collect(Collectors.joining("\n", "", "\n"));
    }

    @Transactional
    @CacheEvict(value = {"aggregateConfig"}, allEntries = true)
    public void deleteTable(Long id) {
        RewriteTable table = require(id);
        List<Route> references = routeRepository.findByRewriteTableId(id);
        if (!references.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "改写表“" + table.getName() + "”被以下路由引用，无法删除：" + OutboundService.routeNames(references));
        }
        rewriteEntryRepository.deleteByTableId(id);
        rewriteTableRepository.delete(table);
    }

    /** Parses {@code pattern host:port [notes]} lines; blank lines and # comments are skipped. */
    List<RewriteEntryDTO> parse(String text) {
        List<RewriteEntryDTO> entries = new ArrayList<>();
        String[] lines = text == null ? new String[0] : text.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] parts = line.split("\\s+", 3);
            if (parts.length < 2) throw badRequest("第 " + (i + 1) + " 行格式应为：域名模式 host:port [备注]");
            int colon = parts[1].lastIndexOf(':');
            if (colon <= 0 || colon == parts[1].length() - 1) {
                throw badRequest("第 " + (i + 1) + " 行的改写目标必须为 host:port");
            }
            RewriteEntryDTO entry = new RewriteEntryDTO();
            entry.setDomainPattern(parts[0]);
            String host = parts[1].substring(0, colon);
            entry.setTargetHost(host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host);
            try {
                entry.setTargetPort(Integer.parseInt(parts[1].substring(colon + 1)));
            } catch (NumberFormatException e) {
                throw badRequest("第 " + (i + 1) + " 行的端口无效: " + parts[1]);
            }
            entry.setEnabled(true);
            entry.setNotes(parts.length > 2 ? parts[2] : null);
            entries.add(entry);
        }
        return entries;
    }

    private List<RewriteEntryDTO> normalize(List<RewriteEntryDTO> entries) {
        List<RewriteEntryDTO> normalized = new ArrayList<>();
        Set<String> patterns = new HashSet<>();
        for (RewriteEntryDTO entry : entries == null ? List.<RewriteEntryDTO>of() : entries) {
            String pattern = entry.getDomainPattern() == null ? "" : entry.getDomainPattern().trim().toLowerCase(Locale.ROOT);
            if (pattern.endsWith(".") && !pattern.equals(".")) pattern = pattern.substring(0, pattern.length() - 1);
            if (!DOMAIN_PATTERN.matcher(pattern).matches()) {
                throw badRequest("域名模式无效（支持 a.com、.a.com、*.a.com）: " + entry.getDomainPattern());
            }
            if (!patterns.add(pattern)) throw badRequest("改写表中域名模式重复: " + pattern);
            if (entry.getTargetHost() == null || entry.getTargetHost().isBlank()) {
                throw badRequest("改写目标不能为空: " + pattern);
            }
            if (entry.getTargetPort() == null || entry.getTargetPort() < 1 || entry.getTargetPort() > 65535) {
                throw badRequest("改写端口必须在 1-65535 之间: " + pattern);
            }
            RewriteEntryDTO copy = new RewriteEntryDTO();
            copy.setDomainPattern(pattern);
            copy.setTargetHost(entry.getTargetHost().trim());
            copy.setTargetPort(entry.getTargetPort());
            copy.setEnabled(entry.getEnabled() == null || entry.getEnabled());
            copy.setNotes(entry.getNotes());
            normalized.add(copy);
        }
        return normalized;
    }

    /** Aligns the new list with existing entries by pattern so unchanged entries keep their IDs. */
    private void replaceEntries(Long tableId, List<RewriteEntryDTO> entries) {
        Map<String, RewriteEntry> existing = rewriteEntryRepository.findByTableIdOrderBySortOrderAscIdAsc(tableId).stream()
                .collect(Collectors.toMap(entry -> entry.getDomainPattern().toLowerCase(Locale.ROOT), Function.identity()));
        Set<String> kept = new HashSet<>();
        List<RewriteEntry> toSave = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            RewriteEntryDTO dto = entries.get(i);
            RewriteEntry entry = existing.get(dto.getDomainPattern());
            if (entry == null) {
                entry = new RewriteEntry();
                entry.setTableId(tableId);
            }
            kept.add(dto.getDomainPattern());
            entry.setSortOrder(i);
            entry.setDomainPattern(dto.getDomainPattern());
            entry.setTargetHost(dto.getTargetHost());
            entry.setTargetPort(dto.getTargetPort());
            entry.setEnabled(dto.getEnabled());
            entry.setNotes(dto.getNotes());
            toSave.add(entry);
        }
        List<RewriteEntry> removed = existing.entrySet().stream()
                .filter(e -> !kept.contains(e.getKey())).map(Map.Entry::getValue).toList();
        rewriteEntryRepository.deleteAll(removed);
        rewriteEntryRepository.flush();
        rewriteEntryRepository.saveAll(toSave);
    }

    private List<RewriteEntryDTO> entriesOf(Long tableId) {
        return rewriteEntryRepository.findByTableIdOrderBySortOrderAscIdAsc(tableId).stream().map(entry -> {
            RewriteEntryDTO dto = new RewriteEntryDTO();
            dto.setId(entry.getId());
            dto.setDomainPattern(entry.getDomainPattern());
            dto.setTargetHost(entry.getTargetHost());
            dto.setTargetPort(entry.getTargetPort());
            dto.setEnabled(entry.getEnabled());
            dto.setNotes(entry.getNotes());
            return dto;
        }).toList();
    }

    private RewriteTableView toView(RewriteTable table, long routeCount) {
        RewriteTableView view = new RewriteTableView();
        view.setId(table.getId());
        view.setName(table.getName());
        view.setNotes(table.getNotes());
        view.setEntries(entriesOf(table.getId()));
        view.setRouteCount(routeCount);
        view.setCreatedAt(table.getCreatedAt());
        view.setUpdatedAt(table.getUpdatedAt());
        return view;
    }

    private static String formatTarget(String host, Integer port) {
        return (host.contains(":") ? "[" + host + "]" : host) + ":" + port;
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
