/* Local UI fixture verification; does not log into or write to a deployed gateway. */
const { chromium } = require('playwright');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');

(async () => {
  const base = process.env.E2E_BASE_URL || 'http://127.0.0.1:18767';
  const output = process.env.E2E_SCREENSHOT_DIR || '/tmp/gateway-distribution-screenshots';
  fs.mkdirSync(output, { recursive: true });
  const browser = await chromium.launch({ args: ['--no-sandbox'] });
  try {
    const context = await browser.newContext({ viewport: { width: 1680, height: 1050 }, locale: 'zh-CN', timezoneId: 'Asia/Shanghai' });
    await context.addInitScript(() => localStorage.setItem('token', 'local-preview-fixture'));
    const page = await context.newPage();
    const errors = [];
    async function choose(name, option) {
      const input = page.getByRole('combobox', { name, exact: true });
      await input.focus();
      await input.press('ArrowDown');
      await page.locator('.ant-select-dropdown:visible').getByText(option, { exact: true }).click();
    }
    page.on('pageerror', error => errors.push(error.message));
    await page.route('**/config/**', route => route.request().isNavigationRequest()
      ? route.fulfill({ contentType: 'text/html', body: fs.readFileSync(path.join(__dirname, '../../frontend/build/index.html'), 'utf8') })
      : route.continue());
    await page.route('**/admin/setup-status', route => route.fulfill({ json: { setupRequired: false } }));
    await page.route('**/admin/me', route => route.fulfill({ json: { id: 1, username: 'preview', roles: ['ADMIN'], mustChangePassword: false } }));
    await page.route('**/api/workers', route => route.fulfill({ json: [
      { workerId: 'home-01', displayName: '家庭网关', enabled: true },
      { workerId: 'unavailable', displayName: '查询不可用示例', enabled: false },
    ] }));
    await page.route('**/api/observability/v1/distribution?*', route => {
      const params = new URL(route.request().url()).searchParams;
      if (params.get('workerId') === 'unavailable') return route.fulfill({ status: 503, json: { message: '访问分布暂不可用，请检查新版日志查询服务' } });
      const dimension = params.get('dimension');
      const keys = {
        users: [['42', 'alice'], ['7', 'bob'], ['', '']],
        targets: [['example.com'], ['video.example.com'], ['']],
        user_targets: [['42', 'alice', 'example.com'], ['7', 'bob', 'video.example.com'], ['', '', '']],
        routes: [['1', '默认直连'], ['2', '家庭服务重写'], ['', '']],
        client_ips: [['192.168.1.2'], ['192.168.1.3'], ['']],
        categories: [['EXTERNAL'], ['INTERNAL_REWRITE'], ['UNKNOWN']],
      }[dimension];
      const counters = [
        { connections: '400', upload: '5242880', download: '20971520', bytes: '26214400' },
        { connections: '300', upload: '1048576', download: '4194304', bytes: '5242880' },
        { connections: '100', upload: '0', download: '0', bytes: '0' },
      ];
      const metric = params.get('metric');
      const totals = { connections: '1000', upload: '7340032', download: '27262976', bytes: '34603008' };
      const items = counters.map((counter, index) => ({ keys: keys[index], counters: counter, value: counter[metric] }));
      const top = items.reduce((sum, item) => sum + BigInt(item.value), 0n);
      return route.fulfill({ json: {
        from: params.get('from'), to: params.get('to'), dimension, metric, items, totals,
        totalValue: totals[metric], otherValue: (BigInt(totals[metric]) - top).toString(), groups: '6', missingConnections: '100',
        firstSessionAt: '2026-10-01 01:00:00', historyBasis: 'ENDED_SESSIONS_ONLY', timeBoundary: '[from,to)',
      } });
    });
    await page.goto(base + '/config/observability/distribution');
    // Startup auth may redirect to the dashboard before /admin/me completes.
    await page.getByRole('menuitem').filter({ hasText: '访问分布' }).click();
    await page.getByText('40.00%', { exact: true }).waitFor();
    await page.waitForTimeout(800); // Let the chart's initial animation finish before capturing it.
    await page.screenshot({ path: path.join(output, 'desktop.png'), fullPage: true });
    await choose('维度', '用户－目标');
    await choose('指标', '总流量');
    await page.getByRole('cell', { name: 'alice (#42) → example.com', exact: true }).waitFor();
    await page.waitForTimeout(800);
    await page.screenshot({ path: path.join(output, 'targets.png'), fullPage: true });
    await page.setViewportSize({ width: 390, height: 844 });
    await page.waitForTimeout(400);
    await page.screenshot({ path: path.join(output, 'mobile.png'), fullPage: true });
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth);
    assert.equal(overflow, false, 'Mobile document must not overflow horizontally');
    await choose('代理节点', '查询不可用示例（已停用）');
    await page.getByRole('alert').waitFor();
    assert.equal(await page.locator('.distribution-summary').count(), 0, 'Failed queries must not show zero/stale totals');
    assert.deepEqual(errors, []);
    process.stdout.write(JSON.stringify({ screenshots: output, pageErrors: errors, mobileOverflow: overflow }) + '\n');
  } finally { await browser.close(); }
})().catch(error => { process.stderr.write(error.stack + '\n'); process.exitCode = 1; });
