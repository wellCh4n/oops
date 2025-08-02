'use client';

import EnvironmentEditor from '@/component/EnvironmentEditor';
import { useHeader } from '@/context/header-context';
import { fetchEnvironmentList } from '@/service/environment';
import { EnvironmentItem } from '@/types/environment';
import { Button, Card, Modal, Table, Tabs } from 'antd';
import React, { useEffect, useState } from 'react';

export default function SystemPage() {

  const [environments, setEnvironments] = useState<EnvironmentItem[]>([]);
  const { setHeaderContent } = useHeader();

  const fetchEnvironments = async () => {
    const data = await fetchEnvironmentList();
    setEnvironments(data);
  }

  useEffect(() => {
    setHeaderContent(
      <span>System</span>
    )
    return () => {
      setHeaderContent("");
    }
  }, [ setHeaderContent ])

  useEffect(() => {
    fetchEnvironments()
  }, [])

  type EnvironmentPanelProps = {
    environments: EnvironmentItem[];
  }

  const EnvironmentPanel: React.FC<EnvironmentPanelProps> = ({ environments }) => {
    return (
      <div>
        <Table<EnvironmentItem>
          rowKey={(record) => record.id}
          dataSource={environments}
          columns={[
            {
              key: 'id',
              title: 'ID',
              dataIndex: 'id',
            },
            {
              key: 'name',
              title: 'Name',
              dataIndex: 'name',
            },
            {
              key: 'apiServerUrl',
              title: 'API Server',
              dataIndex: 'apiServerUrl',
            },
            {
              key: 'action',
              title: 'Action',
              render: (_, record) => {
                return (
                  <Button onClick={() => {
                    const modal = Modal.confirm({
                      title: 'Edit Environment',
                      footer: null,
                      content: (
                        <EnvironmentEditor 
                          environment={record} 
                          onFinish={() => {
                            modal.destroy();
                            fetchEnvironments();
                          }}
                          mode="edit"
                        />
                      )
                    })
                  }}>Edit</Button>
                )
              }
            },
          ]}
        />
      </div>
    )
  }

  return (
    <Card>
      <Tabs
        items={[
          {
            key: 'environments',
            label: 'Environments',
            children: <EnvironmentPanel environments={environments} />,
          },
        ]}
      />
      {/* <Form 
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
      </Form> */}
    </Card>
  );
}