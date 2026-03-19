import React from 'react';
import {
  Alert,
  Button,
  Card,
  Col,
  Divider,
  Row,
  Space,
  Tag,
  Typography,
} from 'antd';
import {
  ArrowLeftOutlined,
  BookOutlined,
  CloudDownloadOutlined,
  GlobalOutlined,
  LinkOutlined,
  RobotOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import {
  RULE_SET_SOURCE_LABELS,
  RuleSetSourceType,
} from '../types/ruleset';
import './RuleSetGuide.css';

const { Title, Paragraph, Text } = Typography;

const sourceTypeCards = [
  {
    key: RuleSetSourceType.GIT_RAW_FILE,
    icon: <LinkOutlined />,
    title: 'Git Raw 文件',
    description: '直接读取 GitHub 仓库里的某个文本文件或 YAML 文件，最适合接入 v2fly 和 blackmatrix7 这类长期维护的规则仓库。',
    urlExample: 'https://raw.githubusercontent.com/blackmatrix7/ios_rule_script/master/rule/Clash/OpenAI/OpenAI.yaml',
    recommendation: '如果你是从 GitHub 仓库文件页点了 Raw，再复制地址，通常就选它。',
  },
  {
    key: RuleSetSourceType.HTTP_FILE,
    icon: <GlobalOutlined />,
    title: 'HTTP 文件',
    description: '读取任意可访问的 HTTP 或 HTTPS 地址，适合公司内网镜像、对象存储、自建规则服务或第三方直链。',
    urlExample: 'https://rules.example.com/geo/geolocation-not-cn.txt',
    recommendation: '如果文件不在 GitHub Raw，而是在你自己的规则服务、CDN 或对象存储上，就选它。',
  },
  {
    key: RuleSetSourceType.GITHUB_RELEASE_ASSET,
    icon: <CloudDownloadOutlined />,
    title: 'GitHub Release 资产',
    description: '读取 GitHub Releases 页面挂载的发布产物。常见于规则仓库预编译后的 dat、yaml、txt 资产。',
    urlExample: 'https://github.com/<owner>/<repo>/releases/download/<tag>/<asset-name>',
    recommendation: '如果你是在 Releases 页面复制下载地址，而不是在仓库文件页复制 Raw 地址，就选它。',
  },
];

const formatCards = [
  {
    title: 'domain-list-community',
    useFor: 'v2fly/domain-list-community 一类的规则源',
    example: `domain:openai.com\nfull:chat.openai.com\nkeyword:gpt`,
  },
  {
    title: 'Clash Classical',
    useFor: 'blackmatrix7/ios_rule_script 一类的 Clash YAML 规则',
    example: `payload:\n  - DOMAIN-SUFFIX,openai.com\n  - DOMAIN,chat.openai.com\n  - DOMAIN-KEYWORD,gpt`,
  },
  {
    title: 'Plain Domain List',
    useFor: '每行一个域名的纯文本列表',
    example: `openai.com\nchat.openai.com\napi.openai.com`,
  },
];

const fieldGuides = [
  {
    field: '规则集 Key',
    detail: '内部唯一标识。路由规则最终引用的是它，不是外部 URL。建议写成 ai-openai、geo-not-cn 这种稳定 key。',
  },
  {
    field: '分类',
    detail: '用于管理和筛选。AI 规则集选 AI，地理位置规则集选 GEO。',
  },
  {
    field: '匹配目标',
    detail: '当前外部同步的主场景是 DOMAIN。也就是说现在主要同步域名规则，而不是 IP 段。',
  },
  {
    field: '来源类型',
    detail: '告诉系统这个 URL 是从哪里来的。当前 P0 里三种外部源最后都会走 HTTP 下载，但来源类型会影响配置语义和团队维护可读性。',
  },
  {
    field: '规则源 URL',
    detail: '要填可以直接下载文本内容的地址，而不是 GitHub 网页浏览地址。重点是最终能返回规则文本或 YAML。',
  },
  {
    field: '解析格式',
    detail: '必须和文件内容匹配。选错后通常会同步成功但解析不到规则，或者规则数量异常。',
  },
  {
    field: '发布到 Worker',
    detail: '只有发布后的规则集才能在路由规则里被引用。',
  },
];

const examples = [
  {
    title: 'OpenAI 规则集',
    summary: '适合把 OpenAI 域名独立分流到特定出口或代理节点。',
    tags: ['AI', '推荐', 'Git Raw 文件'],
    formValues: `规则集 Key: ai-openai
名称: OpenAI
分类: AI
匹配目标: DOMAIN
来源类型: GIT_RAW_FILE
规则源 URL: https://raw.githubusercontent.com/blackmatrix7/ios_rule_script/master/rule/Clash/OpenAI/OpenAI.yaml
解析格式: CLASH_CLASSICAL
启用: 开
发布到 Worker: 开`,
    sourceConfig: `{
  "url": "https://raw.githubusercontent.com/blackmatrix7/ios_rule_script/master/rule/Clash/OpenAI/OpenAI.yaml",
  "format": "CLASH_CLASSICAL"
}`,
    routeUsage: `conditionType = RULE_SET
value = ai-openai`,
  },
  {
    title: '地理位置非中国规则集',
    summary: '适合把 geolocation-!cn 类型的域名统一走境外出口。',
    tags: ['GEO', '推荐', 'Git Raw 文件'],
    formValues: `规则集 Key: geo-not-cn
名称: Geolocation Not CN
分类: GEO
匹配目标: DOMAIN
来源类型: GIT_RAW_FILE
规则源 URL: https://raw.githubusercontent.com/v2fly/domain-list-community/master/data/geolocation-!cn
解析格式: DOMAIN_LIST_COMMUNITY
启用: 开
发布到 Worker: 开`,
    sourceConfig: `{
  "url": "https://raw.githubusercontent.com/v2fly/domain-list-community/master/data/geolocation-!cn",
  "format": "DOMAIN_LIST_COMMUNITY"
}`,
    routeUsage: `conditionType = RULE_SET
value = geo-not-cn`,
  },
  {
    title: '地理位置中国规则集',
    summary: '适合把中国站点优先直连，把国外站点交给其他规则处理。',
    tags: ['GEO', '常用', 'Git Raw 文件'],
    formValues: `规则集 Key: geo-cn
名称: Geolocation CN
分类: GEO
匹配目标: DOMAIN
来源类型: GIT_RAW_FILE
规则源 URL: https://raw.githubusercontent.com/v2fly/domain-list-community/master/data/cn
解析格式: DOMAIN_LIST_COMMUNITY
启用: 开
发布到 Worker: 开`,
    sourceConfig: `{
  "url": "https://raw.githubusercontent.com/v2fly/domain-list-community/master/data/cn",
  "format": "DOMAIN_LIST_COMMUNITY"
}`,
    routeUsage: `conditionType = RULE_SET
value = geo-cn`,
  },
  {
    title: '公司内部镜像规则集',
    summary: '如果你们已经把 GitHub 规则同步到了内网或 CDN，界面里就用 HTTP 文件。',
    tags: ['HTTP', '内网', '镜像'],
    formValues: `规则集 Key: ai-openai-mirror
名称: OpenAI Mirror
分类: AI
匹配目标: DOMAIN
来源类型: HTTP_FILE
规则源 URL: https://rules.example.com/ai/openai.yaml
解析格式: CLASH_CLASSICAL
启用: 开
发布到 Worker: 开`,
    sourceConfig: `{
  "url": "https://rules.example.com/ai/openai.yaml",
  "format": "CLASH_CLASSICAL"
}`,
    routeUsage: `conditionType = RULE_SET
value = ai-openai-mirror`,
  },
];

const RuleSetGuide: React.FC = () => {
  const navigate = useNavigate();

  return (
    <div className="rule-set-guide">
      <section className="rule-set-guide-hero">
        <div className="rule-set-guide-hero-content">
          <Tag color="blue" className="rule-set-guide-badge">
            <BookOutlined /> 规则源说明
          </Tag>
          <Title level={2} className="rule-set-guide-title">
            规则集怎么配，先看这里
          </Title>
          <Paragraph className="rule-set-guide-subtitle">
            这个页面只回答两个问题：外部规则源类型到底有什么区别，以及你在界面里应该怎么填。
          </Paragraph>
          <Space wrap>
            <Button type="primary" icon={<ArrowLeftOutlined />} onClick={() => navigate('/config/rule-sets')}>
              返回规则集管理
            </Button>
            <Button icon={<RobotOutlined />} onClick={() => navigate('/config/routing')}>
              去看路由规则
            </Button>
          </Space>
        </div>
      </section>

      <Alert
        type="info"
        showIcon
        message="当前 P0 实现的真实行为"
        description="GIT_RAW_FILE、HTTP_FILE、GITHUB_RELEASE_ASSET 在当前版本里最终都会由控制面通过 HTTP/HTTPS 拉取内容，再按你选择的解析格式解析。它们的主要区别在于来源语义、URL 来源和团队维护方式，而不是 Worker 侧执行逻辑。"
      />

      <Row gutter={[16, 16]}>
        <Col xs={24} lg={8}>
          <Card className="rule-set-guide-quick-card">
            <Title level={4}>如果你只想先配出来</Title>
            <Paragraph>
              不知道怎么选来源类型时，优先选 <Text strong>Git Raw 文件</Text>。
            </Paragraph>
            <Paragraph>
              不知道怎么选格式时，<Text strong>v2fly</Text> 通常配 <Text code>DOMAIN_LIST_COMMUNITY</Text>，
              <Text strong>blackmatrix7</Text> 通常配 <Text code>CLASH_CLASSICAL</Text>。
            </Paragraph>
            <Paragraph style={{ marginBottom: 0 }}>
              路由规则里不要再填 GitHub 地址，最终只引用内部 <Text code>ruleKey</Text>。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} lg={16}>
          <Card className="rule-set-guide-quick-card">
            <Title level={4}>推荐选择顺序</Title>
            <div className="rule-set-guide-step-list">
              <div className="rule-set-guide-step">
                <span className="rule-set-guide-step-index">1</span>
                <div>
                  <Text strong>先看文件从哪里来</Text>
                  <Paragraph>GitHub 仓库文件页复制 Raw 地址，用 Git Raw；公司镜像和任意直链，用 HTTP；Releases 下载页复制资产地址，用 GitHub Release 资产。</Paragraph>
                </div>
              </div>
              <div className="rule-set-guide-step">
                <span className="rule-set-guide-step-index">2</span>
                <div>
                  <Text strong>再看文件长什么样</Text>
                  <Paragraph>如果文件里有 domain: / full: / keyword: 前缀，选 domain-list-community；如果是 payload YAML，选 Clash Classical。</Paragraph>
                </div>
              </div>
              <div className="rule-set-guide-step">
                <span className="rule-set-guide-step-index">3</span>
                <div>
                  <Text strong>最后在路由里引用 ruleKey</Text>
                  <Paragraph style={{ marginBottom: 0 }}>例如你创建了 ai-openai，路由页就只选择 ai-openai，不再关心上游 URL。</Paragraph>
                </div>
              </div>
            </div>
          </Card>
        </Col>
      </Row>

      <Divider orientation="left">来源类型区别</Divider>
      <Row gutter={[16, 16]}>
        {sourceTypeCards.map((item) => (
          <Col xs={24} lg={8} key={item.key}>
            <Card className="rule-set-guide-card" title={<Space>{item.icon}<span>{item.title}</span></Space>}>
              <Tag color="blue">{RULE_SET_SOURCE_LABELS[item.key]}</Tag>
              <Paragraph>{item.description}</Paragraph>
              <Text strong>典型 URL</Text>
              <pre className="rule-set-guide-code">
                <code>{item.urlExample}</code>
              </pre>
              <Paragraph style={{ marginBottom: 0 }}>
                <Text strong>怎么选：</Text>
                {item.recommendation}
              </Paragraph>
            </Card>
          </Col>
        ))}
      </Row>

      <Divider orientation="left">解析格式怎么选</Divider>
      <Row gutter={[16, 16]}>
        {formatCards.map((item) => (
          <Col xs={24} lg={8} key={item.title}>
            <Card className="rule-set-guide-card" title={item.title}>
              <Paragraph>{item.useFor}</Paragraph>
              <pre className="rule-set-guide-code">
                <code>{item.example}</code>
              </pre>
            </Card>
          </Col>
        ))}
      </Row>

      <Divider orientation="left">界面字段解释</Divider>
      <Row gutter={[16, 16]}>
        {fieldGuides.map((item) => (
          <Col xs={24} md={12} key={item.field}>
            <Card className="rule-set-guide-card">
              <Text strong>{item.field}</Text>
              <Paragraph style={{ marginTop: 8, marginBottom: 0 }}>{item.detail}</Paragraph>
            </Card>
          </Col>
        ))}
      </Row>

      <Divider orientation="left">可直接照抄的配置案例</Divider>
      <div className="rule-set-guide-examples">
        {examples.map((example) => (
          <Card key={example.title} className="rule-set-guide-example-card">
            <Space wrap style={{ marginBottom: 12 }}>
              <Title level={4} style={{ margin: 0 }}>{example.title}</Title>
              {example.tags.map((tag) => (
                <Tag key={tag} color="geekblue">{tag}</Tag>
              ))}
            </Space>
            <Paragraph>{example.summary}</Paragraph>
            <Row gutter={[16, 16]}>
              <Col xs={24} xl={10}>
                <Text strong>界面里这样填</Text>
                <pre className="rule-set-guide-code">
                  <code>{example.formValues}</code>
                </pre>
              </Col>
              <Col xs={24} xl={8}>
                <Text strong>后端实际收到的 sourceConfig</Text>
                <pre className="rule-set-guide-code">
                  <code>{example.sourceConfig}</code>
                </pre>
              </Col>
              <Col xs={24} xl={6}>
                <Text strong>后续路由怎么引用</Text>
                <pre className="rule-set-guide-code">
                  <code>{example.routeUsage}</code>
                </pre>
              </Col>
            </Row>
          </Card>
        ))}
      </div>

      <Card className="rule-set-guide-footer-card">
        <Title level={4}>最后只记一件事</Title>
        <Paragraph style={{ marginBottom: 0 }}>
          外部规则源是给 <Text strong>规则集管理页</Text> 用的，路由页永远只选内部规则集 key。也就是：
          先把 OpenAI 配成 <Text code>ai-openai</Text>，再在路由里引用 <Text code>ai-openai</Text>。
        </Paragraph>
      </Card>
    </div>
  );
};

export default RuleSetGuide;
