#!/usr/bin/env python3
"""将 MySQL 备份（mysqldump，可 .gz）转换为控制端 SQLite 库 home-proxy.db。

流程见 docs/development/controller-sqlite-migration-design.md §6：
  1. 临时 mysql 容器还原备份（结束即销毁，无数据卷）；
  2. 用 V1__baseline.sql 建空库（与应用同一份 schema）；
  3. 按外键顺序复制保留的表，写入与 Flyway 一致的 flyway_schema_history；
  4. 校验：行数/内容摘要、foreign_key_check、integrity_check、json_valid、序列、大小写重复；
  5. 导出等价的 SQL 脚本（可选）并打印对账报告。

只依赖 Python 3 标准库与 docker；输入文件只读，输出永远是全新文件。
用法：
  python3 -I scripts/migration/mysql-to-sqlite.py BACKUP.sql.gz --out .tmp/home-proxy.db [--sql .tmp/home-proxy.sql]
"""
import argparse
import binascii
import gzip
import hashlib
import json
import re
import sqlite3
import subprocess
import sys
import time
import uuid
from datetime import datetime
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
BASELINE = REPO / "backend/control-manager/src/main/resources/db/migration/V1__baseline.sql"
MYSQL_IMAGE = "mysql:8.4"
SCHEMA = "tpproxy"

# 外键依赖顺序（父表在前）。不在此列表且不在 SKIPPED 里的备份表视为意外，直接失败。
TABLE_ORDER = [
    "admin_user", "admin_login_history", "admin_token_blacklist",
    "users", "routes", "rule_set", "rule_set_payload",
    "proxy_worker", "inbound_configs", "worker_status", "worker_tasks",
    "wol_configs", "disk_monitor_host", "disk_monitor_sample", "disk_monitor_setting",
    "mail_gateway", "mail_target", "mail_send_log", "scheduled_task",
]
# 阶段 1 已确认删除的表 + Flyway 历史表（新库自建）。
SKIPPED = {
    "flyway_schema_history", "rate_limits", "dns_rule", "worker_metric_samples",
    "http_gateways", "http_virtual_hosts", "http_tls_certificates", "http_tls_certificate_versions",
    "agg_minute_traffic_stats",
    *(f"agg_{p}_{k}_stats" for p in ("day", "month")
      for k in ("app", "dst_geo", "src_geo", "user_app", "user")),
}
# 备份里存在、基线刻意不含的列（plan §3.3 差异 1）。
DROPPED_COLUMNS = {
    "worker_status": {
        "http_gateway_client_connection_count",
        "http_gateway_upstream_active_connection_count",
        "http_gateway_upstream_idle_connection_count",
    },
}
# 大小写不敏感（NOCASE）且需做重复检查的列由 PRAGMA 动态读取基线得到，无需手写。
KEY_COLUMNS = {  # 关键列校验和（设计 §6.4 第 2 项）
    "users": ["username"], "routes": ["name"], "inbound_configs": ["protocol", "listen_ip", "port"],
}
INT_TYPES = {"tinyint", "smallint", "mediumint", "int", "integer", "bigint", "bit", "year"}
BIN_TYPES = {"binary", "varbinary", "blob", "tinyblob", "mediumblob", "longblob"}
DATETIME_TYPES = {"datetime", "timestamp"}
FLOAT_TYPES = {"float", "double", "decimal"}


def log(msg):
    print(msg, file=sys.stderr, flush=True)


def sh(args, **kw):
    return subprocess.run(args, check=True, **kw)


class TempMysql:
    """一次性 mysql 容器；通过 docker exec 访问，不暴露端口，tmpfs 存储，退出即销毁。"""

    def __init__(self):
        self.name = f"home-proxy-migration-{uuid.uuid4().hex[:8]}"
        self.password = uuid.uuid4().hex

    def __enter__(self):
        sh(["docker", "run", "-d", "--rm", "--name", self.name, "--network", "none",
            "--tmpfs", "/var/lib/mysql", "-e", f"MYSQL_ROOT_PASSWORD={self.password}", MYSQL_IMAGE],
           stdout=subprocess.DEVNULL)
        deadline = time.time() + 180
        while time.time() < deadline:
            r = subprocess.run(self._cmd(["-e", "SELECT 1"]), capture_output=True)
            if r.returncode == 0:
                return self
            time.sleep(2)
        raise RuntimeError("临时 MySQL 启动超时")

    def __exit__(self, *exc):
        subprocess.run(["docker", "rm", "-f", self.name], capture_output=True)

    def _cmd(self, extra, interactive=False):
        cmd = ["docker", "exec"] + (["-i"] if interactive else []) + \
              ["-e", f"MYSQL_PWD={self.password}", self.name, "mysql", "-uroot", "--default-character-set=utf8mb4"]
        return cmd + extra

    def restore(self, path):
        opener = gzip.open if str(path).endswith(".gz") else open
        with opener(path, "rb") as f:
            proc = subprocess.Popen(self._cmd([], interactive=True), stdin=subprocess.PIPE)
            while chunk := f.read(1 << 20):
                proc.stdin.write(chunk)
            proc.stdin.close()
            if proc.wait() != 0:
                raise RuntimeError("还原备份失败")

    def query(self, sql):
        """返回 [[str|None]]；--batch 下 NULL 输出为 NULL。调用方保证数据列都经 HEX() 包装，不会与 NULL 冲突。"""
        r = subprocess.run(self._cmd(["--batch", "--raw", "--skip-column-names", "--max-allowed-packet=1G", "-D", SCHEMA, "-e", sql]),
                           capture_output=True)
        if r.returncode != 0:
            raise RuntimeError(f"MySQL 查询失败: {r.stderr.decode('utf-8', 'replace')}")
        out = r.stdout.decode("utf-8")
        return [[None if v == "NULL" else v for v in line.split("\t")] for line in out.split("\n") if line != ""]


def fmt_datetime(s):
    m = re.fullmatch(r"(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d)(?:\.(\d+))?", s)
    if not m:
        raise ValueError(f"无法识别的时间值: {s!r}")
    return f"{m.group(1)}.{(m.group(2) or '').ljust(6, '0')}"


def convert(hexval, dtype):
    if hexval is None:
        return None
    raw = binascii.unhexlify(hexval)
    if dtype in BIN_TYPES:
        return raw
    text = raw.decode("utf-8")
    if dtype in INT_TYPES:
        return int(text)
    if dtype in FLOAT_TYPES:
        return float(text)
    if dtype in DATETIME_TYPES:
        return fmt_datetime(text)
    if dtype == "date":
        return text
    return text  # varchar/text/json 等


def canon(v):
    """用于两侧比对的规范化文本。"""
    if v is None:
        return "N"
    if isinstance(v, bytes):
        return "B" + v.hex()
    if isinstance(v, float):
        return "F" + repr(v)
    return ("I" if isinstance(v, int) else "T") + str(v)


def digest(rows):
    h = hashlib.sha256()
    for r in rows:
        h.update(("\x1f".join(canon(c) for c in r) + "\x1e").encode("utf-8"))
    return h.hexdigest()[:16]


def flyway_checksum(path):
    """Flyway 迁移校验和：对每行（去 BOM、去行尾）的 UTF-8 字节依次做 CRC32，结果按有符号 32 位。"""
    text = path.read_bytes().decode("utf-8")
    if text.startswith("\ufeff"):
        text = text[1:]
    crc = 0
    for line in text.splitlines():
        crc = binascii.crc32(line.encode("utf-8"), crc)
    return crc - (1 << 32) if crc >= (1 << 31) else crc


def write_flyway_history(db, user="mysql-to-sqlite"):
    db.execute('''CREATE TABLE "flyway_schema_history" (
        "installed_rank" INT NOT NULL PRIMARY KEY, "version" VARCHAR(50), "description" VARCHAR(200) NOT NULL,
        "type" VARCHAR(20) NOT NULL, "script" VARCHAR(1000) NOT NULL, "checksum" INT,
        "installed_by" VARCHAR(100) NOT NULL, "installed_on" TIMESTAMP NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f','now')),
        "execution_time" INT NOT NULL, "success" BOOLEAN NOT NULL)''')
    db.execute('CREATE INDEX "flyway_schema_history_s_idx" ON "flyway_schema_history" ("success")')
    db.execute('INSERT INTO "flyway_schema_history" ("installed_rank","version","description","type","script","checksum",'
               '"installed_by","execution_time","success") VALUES (1,\'1\',\'baseline\',\'SQL\',?,?,?,0,1)',
               (BASELINE.name, flyway_checksum(BASELINE), user))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("backup")
    ap.add_argument("--out", required=True, help="输出 SQLite 文件，必须不存在")
    ap.add_argument("--sql", help="同时导出等价 SQL 脚本")
    ap.add_argument("--report", help="对账报告 JSON 路径")
    args = ap.parse_args()

    out = Path(args.out)
    if out.exists():
        sys.exit(f"{out} 已存在，拒绝覆盖")
    out.parent.mkdir(parents=True, exist_ok=True)

    report, failures = {"tables": {}}, []

    with TempMysql() as my:
        log("还原备份到临时 MySQL ...")
        my.restore(args.backup)

        mysql_tables = {r[0] for r in my.query(
            f"SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA='{SCHEMA}' AND TABLE_TYPE='BASE TABLE'")}
        unexpected = mysql_tables - set(TABLE_ORDER) - SKIPPED
        missing = set(TABLE_ORDER) - mysql_tables
        if unexpected:
            sys.exit(f"备份含未登记的表（既不导入也不在跳过清单）: {sorted(unexpected)}")
        if missing:
            sys.exit(f"备份缺少应导入的表: {sorted(missing)}")
        report["skipped_tables"] = {t: int(my.query(f"SELECT COUNT(*) FROM `{t}`")[0][0])
                                    for t in sorted(mysql_tables & SKIPPED) if t != "flyway_schema_history"}
        nonempty_skipped = {t: n for t, n in report["skipped_tables"].items() if n}
        if nonempty_skipped:
            sys.exit(f"被跳过的表含数据，需人工确认: {nonempty_skipped}")

        db = sqlite3.connect(out)
        db.execute("PRAGMA foreign_keys=OFF")
        db.executescript(BASELINE.read_text(encoding="utf-8"))
        db.commit()
        db.isolation_level = None
        db.execute("BEGIN")

        for table in TABLE_ORDER:
            cols = my.query(
                "SELECT COLUMN_NAME, DATA_TYPE FROM information_schema.COLUMNS "
                f"WHERE TABLE_SCHEMA='{SCHEMA}' AND TABLE_NAME='{table}' ORDER BY ORDINAL_POSITION")
            my_cols = {c: t.lower() for c, t in cols}
            lite_cols = [r[1] for r in db.execute(f"PRAGMA table_info('{table}')")]
            dropped = DROPPED_COLUMNS.get(table, set())
            extra = set(my_cols) - set(lite_cols) - dropped
            absent = set(lite_cols) - set(my_cols)
            if extra or absent:
                sys.exit(f"{table} 列不一致: 备份多出 {sorted(extra)}，基线多出 {sorted(absent)}")
            use = [c for c in lite_cols]
            pk = [r[0] for r in my.query(
                "SELECT COLUMN_NAME FROM information_schema.STATISTICS "
                f"WHERE TABLE_SCHEMA='{SCHEMA}' AND TABLE_NAME='{table}' AND INDEX_NAME='PRIMARY' ORDER BY SEQ_IN_INDEX")]
            sel = ", ".join(f"IF(`{c}` IS NULL, NULL, HEX(CAST(`{c}` AS BINARY)))" for c in use)
            order = ", ".join(f"`{c}`" for c in pk) or "1"
            data = my.query(f"SELECT {sel} FROM `{table}` ORDER BY {order}")
            rows = [tuple(convert(v, my_cols[c]) for v, c in zip(r, use)) for r in data]
            my_count = int(my.query(f"SELECT COUNT(*) FROM `{table}`")[0][0])
            if len(rows) != my_count:
                failures.append(f"{table}: 读取行数 {len(rows)} != MySQL COUNT {my_count}")
            ph = ", ".join("?" * len(use))
            db.executemany(f"INSERT INTO `{table}` ({', '.join(f'`{c}`' for c in use)}) VALUES ({ph})", rows)
            back = db.execute(f"SELECT {', '.join(f'`{c}`' for c in use)} FROM `{table}` ORDER BY "
                              + ", ".join(f'`{c}`' for c in (pk or [use[0]]))).fetchall()
            ok = len(back) == my_count and digest(rows) == digest(back)
            if not ok:
                failures.append(f"{table}: SQLite 回读内容与源数据不一致")
            ent = {"mysql_rows": my_count, "sqlite_rows": len(back), "digest": digest(back), "ok": ok}
            if dropped:
                ent["dropped_columns"] = sorted(dropped)
            for kc in KEY_COLUMNS.get(table, []):
                idx = use.index(kc)
                ent.setdefault("key_digests", {})[kc] = digest([(r[idx],) for r in sorted(rows, key=lambda r: canon(r[idx]))])
            # AUTOINCREMENT 序列: max(MySQL AUTO_INCREMENT-1, max(id))
            if pk == ["id"]:
                ai = my.query(f"SELECT AUTO_INCREMENT FROM information_schema.TABLES WHERE TABLE_SCHEMA='{SCHEMA}' AND TABLE_NAME='{table}'")[0][0]
                mx = db.execute(f"SELECT COALESCE(MAX(id),0) FROM `{table}`").fetchone()[0]
                seq = max(int(ai) - 1 if ai else 0, mx)
                if seq > 0:
                    if db.execute("UPDATE sqlite_sequence SET seq=? WHERE name=?", (seq, table)).rowcount == 0:
                        db.execute("INSERT INTO sqlite_sequence(name, seq) VALUES (?, ?)", (table, seq))
                ent["sequence"] = seq
            report["tables"][table] = ent
            log(f"  {table}: {my_count} 行")

        write_flyway_history(db)
        db.execute("COMMIT")

        # 校验
        fk = db.execute("PRAGMA foreign_key_check").fetchall()
        if fk:
            failures.append(f"foreign_key_check 有 {len(fk)} 条违规: {fk[:5]}")
        ic = db.execute("PRAGMA integrity_check").fetchall()
        if ic != [("ok",)]:
            failures.append(f"integrity_check: {ic}")
        report["foreign_key_check"], report["integrity_check"] = len(fk), ic[0][0]

        json_bad = {}
        for table in TABLE_ORDER:
            for r in db.execute(f"PRAGMA table_info('{table}')").fetchall():
                col = r[1]
                if col.endswith("_json") or col in ("rules_json", "items_json", "metrics_json", "source_config", "inbound_route_bindings"):
                    n = db.execute(f"SELECT COUNT(*) FROM `{table}` WHERE `{col}` IS NOT NULL AND NOT json_valid(`{col}`)").fetchone()[0]
                    if n:
                        json_bad[f"{table}.{col}"] = n
        for k, n in json_bad.items():
            failures.append(f"json_valid 失败: {k} {n} 行")
        report["json_invalid"] = json_bad

        # 唯一约束/索引在 NOCASE 下的重复检查（MySQL 唯一键本应已阻止，这里再确认一次）
        dups = {}
        for table in TABLE_ORDER:
            for idx in db.execute(f"PRAGMA index_list('{table}')").fetchall():
                if not idx[2]:
                    continue
                icols = [r[2] for r in db.execute(f"PRAGMA index_info('{idx[1]}')").fetchall()]
                keys = ", ".join(f"LOWER(`{c}`)" for c in icols)
                nn = " AND ".join(f"`{c}` IS NOT NULL" for c in icols)  # 唯一键允许多个 NULL
                n = db.execute(f"SELECT COUNT(*) FROM (SELECT 1 FROM `{table}` WHERE {nn} GROUP BY {keys} HAVING COUNT(*)>1)").fetchone()[0]
                if n:
                    dups[f"{table}({','.join(icols)})"] = n
        for k, n in dups.items():
            failures.append(f"唯一键大小写重复: {k} {n} 组")
        report["nocase_duplicates"] = dups
        report["sequences"] = dict(db.execute("SELECT name, seq FROM sqlite_sequence ORDER BY name").fetchall())

        if args.sql:
            Path(args.sql).write_text("\n".join(db.iterdump()) + "\n", encoding="utf-8")
        db.close()

    report["failures"] = failures
    if args.report:
        Path(args.report).write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"{'表':34}{'MySQL':>8}{'SQLite':>8}  摘要")
    for t, e in report["tables"].items():
        print(f"{t:34}{e['mysql_rows']:>8}{e['sqlite_rows']:>8}  {e['digest']} {'OK' if e['ok'] else 'FAIL'}")
    print(f"foreign_key_check 违规: {report['foreign_key_check']}  integrity_check: {report['integrity_check']}")
    print(f"跳过的空表: {len(report['skipped_tables'])} 张")
    if failures:
        print("\n校验失败:\n  " + "\n  ".join(failures))
        out.unlink(missing_ok=True)
        sys.exit(1)
    print(f"\n全部校验通过 -> {out}")


if __name__ == "__main__":
    main()
