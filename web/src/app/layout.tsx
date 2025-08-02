'use client';

import React, { useState } from 'react';
import { ConfigProvider, Layout, Menu } from 'antd';
import type { MenuProps } from 'antd';
import '@ant-design/v5-patch-for-react-19';
import { AntdRegistry } from '@ant-design/nextjs-registry';
import enUS from 'antd/locale/en_US';
import 'antd/dist/reset.css';
import './globals.css';
import { AppstoreOutlined, SettingOutlined, RocketOutlined } from '@ant-design/icons';
import { useRouter, usePathname } from 'next/navigation';
import { HeaderProvider, useHeader } from '@/context/header-context';
import EnvironmentSelector from '@/component/EnvironmentSelector';

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

function LayoutContent({ children }: { children: React.ReactNode }) {
  const [collapsed, setCollapsed] = useState(false);
  const router = useRouter();
  const pathname = usePathname();
  const { headerContent } = useHeader();

  const handleMenuClick: MenuProps['onClick'] = (e) => {
    router.push(e.key);
  };

  return (
    <Layout className="flex align-center justify-center h-full">
      <Sider collapsible collapsed={collapsed} onCollapse={(value) => setCollapsed(value)}>
        <div className="flex h-15 leading-15 items-center justify-center text-white">OOPS</div>
        <Menu theme="dark" selectedKeys={[pathname]} mode="inline" items={items} onClick={handleMenuClick} />
      </Sider>
      <Layout>
        <Header className=" text-white! px-4! flex items-center justify-between">
          <div className="flex-1">
            {headerContent}
          </div>
          <div className="flex-shrink-0">
            <EnvironmentSelector />
          </div>
        </Header>
        <Content>
          {children}
        </Content>
      </Layout>
    </Layout>
  );
}

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="zh-CN" suppressHydrationWarning>
      <body>
        <AntdRegistry>
          <ConfigProvider locale={enUS}>
            <HeaderProvider>
              <LayoutContent>{children}</LayoutContent>
            </HeaderProvider>
          </ConfigProvider>
        </AntdRegistry>
      </body>
    </html>
  );
}
