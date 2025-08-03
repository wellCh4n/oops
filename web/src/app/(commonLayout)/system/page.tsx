'use client';

import EnvironmentEditor from '@/component/EnvironmentEditor';
import { useHeader } from '@/context/header-context';
import { deleteEnvironment, fetchEnvironmentList } from '@/service/environment';
import { EnvironmentItem } from '@/types/environment';
import { Button, Card, Modal, Space, Table, Tabs } from 'antd';
import React, { useEffect, useState } from 'react';


type EnvironmentPanelProps = {
  environments: EnvironmentItem[];
}

const EnvironmentPanel: React.FC<EnvironmentPanelProps> = ({ environments }) => {
  return (
    <div>
      <div className="flex justify-end p-3">
        <Button 
          type="primary"
          onClick={() => {
            Modal.confirm({
              title: 'Create Environment',
              footer: null,
              content: (
                <EnvironmentEditor 
                  environment={{}} 
                  onFinish={() => {
                    window.location.reload();
                  }}
                  mode="create"
                />
              )
            })
          }}
        >
          Create
        </Button>
      </div>
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
                <Space>
                  <Button
                    onClick={() => {
                      Modal.confirm({
                        title: 'Edit Environment',
                        footer: null,
                        content: (
                          <EnvironmentEditor 
                            environment={record} 
                            onFinish={() => {
                              window.location.reload();
                            }}
                            mode="edit"
                          />
                        )
                      })
                    }}>
                      Edit
                  </Button>
                  <Button
                    danger
                    onClick={() => {
                      Modal.confirm({
                        title: 'Delete Environment',
                        content: (
                          <div>
                            <p>Are you sure you want to delete {record.name}?</p>
                          </div>
                        ),
                        onOk: () => {
                          deleteEnvironment(record.id).then(() => {
                            window.location.reload();
                          })
                        }
                      })
                    }}
                  >
                    Delete
                  </Button>
                </Space>
              )
            }
          },
        ]}
      />
    </div>
  )
}

export default function SystemPage() {

  const [environments, setEnvironments] = useState<EnvironmentItem[]>([]);
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
    fetchEnvironmentList().then((data) => {
      setEnvironments(data);
    })
  }, [])

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
    </Card>
  );
}