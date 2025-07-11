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
    label: 'Application',
  },
  {
    key: '/pipeline',
    icon: <RocketOutlined />,
    label: 'Pipeline',
  },
  {
    key: '/system',
    icon: <SettingOutlined />,
    label: 'System',
  },
];

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  const [collapsed, setCollapsed] = useState(false);
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
            <Layout className="flex align-center justify-center h-full">
              <Sider collapsible collapsed={collapsed} onCollapse={(value) => setCollapsed(value)}>
                <div className="flex h-15 leading-15 items-center justify-center text-white">OOPS</div>
                <Menu theme="dark" selectedKeys={[pathname]} mode="inline" items={items} onClick={handleMenuClick} />
              </Sider>
              <Layout>
                <Header className="h-15! text-white!">11</Header>
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
