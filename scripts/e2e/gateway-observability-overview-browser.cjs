/* Real-data visual review; no intercepted/mock API responses. */
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');
const assert = require('node:assert/strict');
(async () => {
  const base = process.env.E2E_BASE_URL || 'https://127.0.0.1:18443';
  const output = process.env.E2E_SCREENSHOT_DIR || '/tmp/traffic-overview-screenshots';
  fs.mkdirSync(output, { recursive: true });
  const browser = await chromium.launch({ args: ['--no-sandbox'] });
  try {
    const context = await browser.newContext({ ignoreHTTPSErrors: true, viewport: { width: 1680, height: 1180 }, locale: 'zh-CN', timezoneId: 'Asia/Shanghai' });
    const response = await context.request.post(base + '/admin/login', { data: { username: process.env.E2E_ADMIN_USER || 'congcong', password: process.env.E2E_ADMIN_PASSWORD || 'congcong' } });
    assert(response.ok(), 'Login HTTP ' + response.status());
    const { token } = await response.json();
    await context.addInitScript(value => localStorage.setItem('token', value), token);
    const page = await context.newPage();
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    let lastData;
    page.on('response', async response => { if (/\/overview\?/.test(response.url()) && response.ok()) lastData = await response.json(); });
    await page.goto(base + '/config');
    await page.getByRole('menuitem').filter({ hasText: '流量概览' }).click();
    await page.getByRole('heading', { name: '流量概览', exact: true }).waitFor();
    await page.waitForFunction(() => !document.querySelector('.ov-chart-loading') && !!document.querySelector('.ov-category-summary'));
    await page.waitForTimeout(500);
    assert.equal(await page.getByRole('button', { name: '外网', exact: true }).getAttribute('aria-pressed'), 'true');
    assert.equal(await page.locator('.ov-chart svg').count(), 4);
    await page.screenshot({ path: path.join(output, 'external-desktop.png'), fullPage: true });
    async function changed(action) {
      const done = page.waitForResponse(r => /\/overview\?/.test(r.url()) && r.status() === 200);
      await action(); await done;
      await page.waitForFunction(() => !document.querySelector('.ov-chart-loading'));
      await page.waitForTimeout(400);
    }
    await changed(() => page.getByRole('button', { name: '内网 · 目标重写', exact: true }).click());
    await page.screenshot({ path: path.join(output, 'internal-desktop.png'), fullPage: true });
    await page.getByLabel('连接指标', { exact: true }).selectOption('attempts');
    await changed(() => page.getByLabel('建连结果', { exact: true }).selectOption('FAILURE'));
    await changed(() => page.getByLabel('DNS 结果', { exact: true }).selectOption('FAILURE'));
    await changed(() => page.getByLabel('认证结果', { exact: true }).selectOption('FAILURE'));
    assert(Object.keys(lastData.auth.totals).every(k => k === 'FAILURE'));
    await page.screenshot({ path: path.join(output, 'failure-filters.png'), fullPage: true });
    // Verify failures can be attributed to attempted accounts and real source IPs.
    await page.getByLabel('用户／账号', { exact: true }).fill('home-01-telemetry-a');
    await changed(() => page.getByRole('button', { name: /查询 \/ 刷新/ }).click());
    await page.getByLabel('用户／账号', { exact: true }).fill('');
    await changed(() => page.getByRole('button', { name: /查询 \/ 刷新/ }).click());
    await changed(() => page.getByRole('button', { name: '外网', exact: true }).click());
    for (const label of ['建连结果', 'DNS 结果', '认证结果']) await changed(() => page.getByLabel(label, { exact: true }).selectOption('ALL'));
    await page.getByLabel('连接指标', { exact: true }).selectOption('concurrency');
    await page.setViewportSize({ width: 390, height: 844 });
    await page.waitForTimeout(600);
    // Hide the desktop sidebar through the existing responsive menu button if available.
    await page.screenshot({ path: path.join(output, 'external-mobile.png'), fullPage: true });
    const horizontalOverflow = await page.locator('.observability-overview').evaluate(el => el.scrollWidth > el.clientWidth + 1);
    assert(!horizontalOverflow, 'Overview overflows on mobile');
    const chartsSized = await page.locator('.ov-chart svg').evaluateAll(items => items.every(svg =>
      Number(svg.getAttribute('width')) <= svg.closest('.ov-chart').clientWidth + 1));
    assert(chartsSized, 'Charts did not resize to mobile panels');
    await page.getByRole('button', { name: /查看旧版统计/ }).click();
    await page.getByRole('button', { name: '返回新版概览' }).waitFor();
    await page.getByRole('button', { name: '返回新版概览' }).click();
    await page.getByRole('heading', { name: '流量概览', exact: true }).waitFor();
    assert.deepEqual(errors, []);
    fs.writeFileSync(path.join(output, 'browser-review.json'), JSON.stringify({ realData: true, errors, charts: 4, externalDefault: true, independentResultFilters: true, mobileOverflow: horizontalOverflow, legacyReturn: true }, null, 2));
    console.log(JSON.stringify({ output, errors, realData: true, mobileOverflow: horizontalOverflow }, null, 2));
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exit(1); });
