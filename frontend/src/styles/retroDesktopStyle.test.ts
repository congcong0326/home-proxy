import { readFileSync } from 'fs';
import { join } from 'path';

const readSource = (relativePath: string) => (
  readFileSync(join(process.cwd(), 'src', relativePath), 'utf8')
);

describe('desktop retro style coverage', () => {
  test('traffic user detail table header uses the retro table header treatment', () => {
    const dashboardCss = readSource('pages/Dashboard.css');

    expect(dashboardCss).toMatch(
      /\.traffic-overview\s+\.user-stats-table\s+thead\s+th\s*\{[^}]*background:\s*var\(--retro-primary\)[^}]*color:\s*var\(--retro-screen-muted\)[^}]*font-family:\s*var\(--retro-font\)/s
    );
  });

  test('mail template cards use the retro panel and screen surfaces', () => {
    const mailCss = readSource('pages/MailGateway.css');

    expect(mailCss).toMatch(
      /\.mail-gateway-page\s+\.template-card\s*\{[^}]*background:\s*var\(--retro-panel\)[^}]*border:\s*2px solid var\(--retro-border\)/s
    );
    expect(mailCss).toMatch(
      /\.mail-gateway-page\s+\.template-item\s*\{[^}]*background:\s*var\(--retro-screen\)[^}]*border:\s*2px solid #000/s
    );
  });

  test('rule set detail drawer uses a dedicated retro drawer skin', () => {
    const ruleSetCss = readSource('pages/RuleSetManagement.css');
    const ruleSetPage = readSource('pages/RuleSetManagement.tsx');

    expect(ruleSetPage).toContain('rootClassName="rule-set-detail-drawer"');
    expect(ruleSetCss).toMatch(
      /\.rule-set-detail-drawer\s+\.ant-drawer-content\s*\{[^}]*background:\s*var\(--retro-panel\)/s
    );
    expect(ruleSetCss).toMatch(
      /\.rule-set-detail-drawer\s+\.ant-drawer-header\s*\{[^}]*background:\s*var\(--retro-primary\)[^}]*border-bottom:\s*2px solid var\(--retro-border\)/s
    );
  });

  test('shared controls have visible retro borders', () => {
    const retroCss = readSource('styles/retro-noc.css');

    expect(retroCss).toMatch(
      /\.ant-input,\s*\.ant-input-affix-wrapper,\s*\.ant-input-number,\s*\.ant-picker,\s*\.ant-select-selector\s*\{[^}]*border:\s*2px solid var\(--retro-border\) !important/s
    );
  });
});
