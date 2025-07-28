'use client';

import { useHeader } from '@/context/header-context';
import { fetchSystem, updateSystem } from '@/service/system';
import { SystemItem } from '@/types/system';
import { Button, Card, Form, Input } from 'antd';
import React, { useEffect, useState } from 'react';

export default function SystemPage() {

  const [ form ] = Form.useForm();
  const [ systemInfos, setSystemInfos ] = useState<SystemItem[]>([]);
  const { setHeaderContent } = useHeader();

  useEffect(() => {
    setHeaderContent(
      <span>System</span>
    )
    return () => {
      setHeaderContent("");
    }
  }, [ setHeaderContent ])

  useEffect(() => {
    fetchSystem().then((data) => {
      setSystemInfos(data)
      data.forEach((item) => {
        form.setFieldValue(item.configKey, item.configValue);
      })
    })
  }, [ form ])

  return (
    <Card>
      <Form 
        layout="vertical"
        onFinish={(values) => {
          const configs = Object.entries(values).map((value) => {
            const [ configKey, configValue ] = value;
            return {
              configKey,
              configValue,
            } as SystemItem
          })
          updateSystem(configs)
        }}
        form={form}
      >
        { systemInfos.map((systemInfo) => {
          return (
            <Form.Item key={systemInfo.configKey} label={systemInfo.configKey} name={systemInfo.configKey}>
              <Input.TextArea value={systemInfo.configValue} autoSize />
            </Form.Item>
          )
        }) }
        <Form.Item>
          <Button type="primary" htmlType="submit">
            Save
          </Button>
        </Form.Item>
      </Form>
    </Card>
  );
}