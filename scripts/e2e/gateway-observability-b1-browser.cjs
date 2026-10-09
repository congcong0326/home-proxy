/* Read-only browser verification. Set NODE_PATH to an installed Playwright module. */
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');
const assert = require('node:assert/strict');
(async () => {
  const base = process.env.E2E_BASE_URL || 'https://127.0.0.1:18443';
  const output = process.env.E2E_SCREENSHOT_DIR || '/tmp/gateway-observability-b1-screenshots';
  fs.mkdirSync(output, { recursive: true });
  const browser = await chromium.launch({ args: ['--no-sandbox'] });
  try {
    const context = await browser.newContext({ ignoreHTTPSErrors: true, viewport: { width: 1680, height: 1050 }, locale: 'zh-CN', timezoneId: 'Asia/Shanghai' });
    const response = await context.request.post(base + '/admin/login', { data: { username: process.env.E2E_ADMIN_USER || 'congcong', password: process.env.E2E_ADMIN_PASSWORD || 'congcong' } });
    if (!response.ok()) throw new Error('Login HTTP ' + response.status());
    const { token } = await response.json();
    await context.addInitScript(value => localStorage.setItem('token', value), token);
    const page = await context.newPage();
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    async function choose(name, label) {
      const select = page.getByRole('combobox', { name, exact: true });
      await select.click();
      await select.fill(label);
      await page.locator('.ant-select-dropdown:visible').getByText(label, { exact: true }).click();
      await select.press('Escape');
      await page.locator('.ant-select-dropdown:visible').waitFor({ state: 'hidden' });
    }
    async function query(kind) {
      const response = page.waitForResponse(r => r.url().includes('/' + kind + '/page?') && r.status() === 200);
      await page.getByRole('button', { name: /查\s*询/ }).click();
      const result = await (await response).json();
      await page.locator('.ant-table-row').first().waitFor();
      return result;
    }
    async function applyScope(kind) {
      const localDate = value => new Date(new Date(value).getTime() + 8 * 3600000).toISOString().slice(0, 19);
      if (process.env.E2E_AUDIT_FROM) await page.getByLabel('开始时间', { exact: true }).fill(localDate(process.env.E2E_AUDIT_FROM));
      if (process.env.E2E_AUDIT_TO) await page.getByLabel('结束时间', { exact: true }).fill(localDate(process.env.E2E_AUDIT_TO));
      return query(kind);
    }
    await page.goto(base + '/config');
    await page.getByRole('menuitem').filter({ hasText: '新版日志审计' }).click();
    await page.getByRole('heading', { name: '新版日志审计' }).waitFor();
    const select = page.locator('.ant-select-selector').first();
    await select.click();
    await page.locator('.ant-select-item-option').filter({ hasText: 'home-01（home-01）' }).click();
    await page.locator('.ant-table-row').first().waitFor();
    await applyScope('sessions');
    for (const name of ['协议', '结果', '建连结果', '流量分类']) await page.getByRole('combobox', { name, exact: true }).waitFor();
    if (await page.getByText('采集与覆盖状态', { exact: true }).count()) throw new Error('Collection diagnostics card remains visible');
    await page.getByText('认证未通过，目标未记录', { exact: true }).first().waitFor();
    await page.getByText('建连：未尝试建连', { exact: true }).first().waitFor();
    await page.getByText('尝试账号：home-01-telemetry-a（未认证）', { exact: true }).first().waitFor();
    const failedRow = page.locator('.ant-table-row').filter({ hasText: '用户名或密码错误' }).first();
    await failedRow.getByRole('button', { name: /查\s*看/ }).click();
    await page.getByRole('dialog').getByText('尝试登录账号', { exact: true }).waitFor();
    await page.getByRole('dialog').getByText('home-01-telemetry-a', { exact: true }).waitFor();
    await page.screenshot({ path: path.join(output, 'session-auth-failure.png'), fullPage: true });
    await page.getByRole('dialog').locator('.ant-drawer-close').click();
    await page.screenshot({ path: path.join(output, 'sessions.png'), fullPage: true });
    // Filter in ClickHouse and open a known rewritten target sample.
    await page.getByRole('textbox', { name: '目标／域名' }).fill('10.254.94.1');
    const filtered = page.waitForResponse(r => r.url().includes('/sessions/page?') && r.url().includes('target=') && r.status() === 200);
    await page.getByRole('button', { name: /查\s*询/ }).click();
    await filtered;
    await page.locator('.ant-table-row').first().getByRole('button', { name: /查\s*看/ }).click();
    await page.getByText('关联日志', { exact: true }).waitFor();
    await page.getByRole('dialog').getByText('目标重写实际生效', { exact: true }).scrollIntoViewIfNeeded();
    await page.screenshot({ path: path.join(output, 'session-detail.png'), fullPage: true });
    // Scroll the drawer to inspect association separately.
    await page.getByText('关联日志', { exact: true }).scrollIntoViewIfNeeded();
    await page.screenshot({ path: path.join(output, 'session-related.png'), fullPage: true });
    await page.getByRole('dialog').locator('.ant-drawer-close').click();
    for (const [label, kind, file] of [['DNS 查询', 'dns', 'dns.png'], ['诊断事件', 'events', 'events.png'], ['认证审计', 'credential-auth', 'auth.png']]) {
      const queried = page.waitForResponse(r => r.url().includes('/' + kind + '/page?') && r.status() === 200);
      await page.getByRole('tab', { name: label, exact: true }).click();
      await queried;
      await page.locator('.ant-table-row').first().waitFor();
      let facts = await applyScope(kind);
      for (const name of ['协议', '结果', kind === 'dns' ? '查询类型' : kind === 'events' ? '事件类型' : '认证方式']) {
        await page.getByRole('combobox', { name, exact: true }).waitFor();
      }
      if (kind === 'dns') {
        assert(facts.items.every(row => row.inboundProtocol === 'DNS_SERVER' && row.transport === 'UDP'));
        await choose('协议', 'DNS 服务');
        await choose('查询类型', 'IPv6 地址（AAAA）');
        await choose('结果', '已应答');
        facts = await query(kind);
        assert(facts.items.every(row => row.qtype === 'AAAA' && row.outcome === 'ANSWERED'));
        await page.locator('.ant-table-tbody').getByText('已应答', { exact: true }).first().waitFor();
      }
      if (kind === 'events') {
        assert(facts.items.every(row => row.clientIp && row.inboundProtocol), 'New events must retain source and protocol');
        await choose('事件类型', '建连结果');
        await choose('结果', '通道已建立');
        facts = await query(kind);
        assert(facts.items.every(row => row.eventType === 'CONNECT_RESULT' && row.outcome === 'CONNECTED' && row.username), 'Connected fixtures must retain known users');
        await page.getByText('通道建立', { exact: true }).first().waitFor();
      }
      if (kind === 'credential-auth') {
        await choose('协议', 'SOCKS5协议');
        await choose('认证方式', 'SOCKS5 用户名密码');
        await choose('结果', '认证失败');
        facts = await query(kind);
        assert(facts.items.every(row => row.authOutcome === 'FAILURE' && row.authMethod === 'SOCKS5_PASSWORD' && row.inboundProtocol === 'SOCKS5'));
        await page.locator('.ant-table-tbody').getByText('认证失败', { exact: true }).first().waitFor();
      }
      await page.screenshot({ path: path.join(output, file), fullPage: true });
      await page.locator('.ant-table-row').first().getByRole('button', { name: /查\s*看/ }).click();
      await page.getByRole('dialog').getByText('稳定记录 ID', { exact: true }).waitFor();
      await page.screenshot({ path: path.join(output, kind + '-detail.png'), fullPage: true });
      await page.getByRole('dialog').locator('.ant-drawer-close').click();
    }
    await page.setViewportSize({ width: 390, height: 844 });
    await page.screenshot({ path: path.join(output, 'mobile.png'), fullPage: true });
    if (errors.length) throw new Error('Browser errors: ' + errors.join('; '));
    const result = { output, browserErrors: errors, fourListsAndDetails: true, backendTargetFilter: true, chineseConnectionResults: true, collectionCardRemoved: true, failedAccountVisible: true, chineseEnumDropdowns: true, dnsProtocolCollected: true, eventContextCollected: true, timezone: 'Asia/Shanghai', mobile: true };
    fs.writeFileSync(path.join(output, 'browser-review.json'), JSON.stringify(result, null, 2));
    console.log(JSON.stringify(result, null, 2));
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exit(1); });
