'use client';

import { useHeader } from '@/context/header-context';
import { fetchSystem } from '@/service/system';
import { SystemItem } from '@/types/system';
import { Card, Form, Input } from 'antd';
import React, { useEffect, useState } from 'react';

export default function SystemPage() {

  const [ systemInfos, setSystemInfos ] = useState<SystemItem[]>([]);
  const { setHeaderContent } = useHeader();

  useEffect(() => {
    setHeaderContent(
      <span>System</span>
    )
  }, [])

  useEffect(() => {
    fetchSystem().then((data) => {
      setSystemInfos(data);
    })
  }, [])

  return (
    <Card>
      <Form layout="vertical">
        { systemInfos.map((systemInfo) => {
          return (
            <Form.Item key={systemInfo.configKey} label={systemInfo.configKey}>
              <Input.TextArea value={systemInfo.configValue} autoSize />
            </Form.Item>
          )
        }) }
      </Form>
    </Card>
  );
}