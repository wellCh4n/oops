'use client';

import { fetchNamespaceList } from '@/service/namespace';
import { fetchPipelines } from '@/service/pipeline';
import { PipelineItem } from '@/types/pipeline';
import { ProTable } from '@ant-design/pro-components';
import { Button, Space } from 'antd';
import React, { useEffect, useState } from 'react';

export default function PipelinePage() {

  const [namespaces, setNamespaces] = useState<string[] | null>(null);
  const [namespaceValueEnum, setNamespaceValueEnum] = useState<Record<string, { text: string }>>({})

  useEffect(() => {
    fetchNamespaceList().then((data) => {
      setNamespaces(data)
      const valueEnum = data.reduce((acc, namespace) => {
        acc[namespace] = { text: namespace }
        return acc
      }, {} as Record<string, { text: string }>)
      setNamespaceValueEnum(valueEnum)
    })
  }, [])

  if (!namespaces) {
    return <></>
  }

  return (
    <div className="p-3">
      <ProTable<PipelineItem>
        options={false}
        rowKey="id"
        columns={[
          {
            title: 'Id',
            dataIndex: 'id',
            search: false
          },
          {
            title: 'Application',
            dataIndex: 'applicationName',
            search: true
          },
          {
            title: 'Namespace',
            dataIndex: 'namespace',
            search: true,
            initialValue: namespaces[0],
            valueEnum: namespaceValueEnum
          },
          {
            title: 'Status',
            dataIndex: 'status',
            search: false
          },
          {
            title: 'Action',
            valueType: 'option',
            render: (_, record) => (
              <Space>
                <Button>Watch</Button>
              </Space>
            ),
          },
        ]}
        request={async (params, sort, filter) => {
          console.log(params)
          if (!params.namespace || !params.applicationName) {
            return {
              data: []
            }
          }
          const data = await fetchPipelines(params.namespace, params.applicationName);
          return {
            data: data
          };
        }}
        cardBordered
      />
    </div>
  );
}