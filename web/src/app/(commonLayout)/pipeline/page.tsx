'use client';

import { useHeader } from '@/context/header-context';
import { fetchNamespaceList } from '@/service/namespace';
import { fetchPipelines, stopPipeline } from '@/service/pipeline';
import { PipelineItem } from '@/types/pipeline';
import { ProTable } from '@ant-design/pro-components';
import { Button, Space } from 'antd';
import React, { useEffect, useState } from 'react';

export default function PipelinePage() {

  const [namespaces, setNamespaces] = useState<string[] | null>(null);
  const [namespaceValueEnum, setNamespaceValueEnum] = useState<Record<string, { text: string }>>({})

  const {setHeaderContent} = useHeader();

  useEffect(() => {
    setHeaderContent(
      <span>Pipeline</span>
    )

    return () => {
      setHeaderContent(null)
    }
  }, [])

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
    <div className="p-3 h-full overflow-y-auto">
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
                <Button href={`/namespace/${record.namespace}/application/${record.applicationName}/pipeline/${record.id}`}>Watch</Button>
                <Button onClick={() => {
                  stopPipeline(record.namespace, record.applicationName, record.id).then((data) => {
                    console.log(data)
                  })
                }}>Stop</Button>
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