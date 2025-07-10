'use client';

import React, { useState } from 'react';
import { ConfigProvider, Layout, Menu, theme } from 'antd';
import '@ant-design/v5-patch-for-react-19';
import { AntdRegistry } from '@ant-design/nextjs-registry';
import zhCN from 'antd/locale/zh_CN';
import 'antd/dist/reset.css';
import './globals.css';
import { AppstoreOutlined, SettingOutlined, RocketOutlined } from '@ant-design/icons';
import { useRouter, usePathname } from 'next/navigation';

const { Header, Content, Sider } = Layout;

const items = [
  {
    key: '/application',
    icon: <AppstoreOutlined />,
    label: '应用',
  },
  {
    key: '/pipeline',
    icon: <RocketOutlined />,
    label: '流水线',
  },
  {
    key: '/system',
    icon: <SettingOutlined />,
    label: '系统',
  },
];

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  const [collapsed, setCollapsed] = useState(false);
  const { token: { colorBgContainer, borderRadiusLG } } = theme.useToken();
  const router = useRouter();
  const pathname = usePathname();

  const handleMenuClick = (e: any) => {
    router.push(e.key);
  };

  return (
    <html lang="zh-CN" suppressHydrationWarning>
      <body>
        <AntdRegistry>
            <ConfigProvider locale={zhCN}>
          <Layout style={{ minHeight: '100vh' }}>
            <Sider collapsible collapsed={collapsed} onCollapse={(value) => setCollapsed(value)}>
              <div className="demo-logo-vertical" />
              <Menu theme="dark" selectedKeys={[pathname]} mode="inline" items={items} onClick={handleMenuClick} />
            </Sider>
            <Layout>
              {/* <Header style={{ padding: 0, background: colorBgContainer }} /> */}
              <Content>
                {children}
              </Content>
            </Layout>
          </Layout>
        </ConfigProvider>
          </AntdRegistry>
      </body>
    </html>
  );
}
