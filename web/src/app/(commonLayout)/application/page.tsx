'use client';

import { ProTable } from '@ant-design/pro-components';
import { Button, Space } from 'antd';
import { ApplicationItem } from '@/types/application';
import { DatabaseOutlined, EditOutlined, InfoCircleOutlined, SendOutlined } from '@ant-design/icons';
import {useEffect, useState} from 'react';
import { useHeader } from '@/context/header-context';
import { fetchNamespaceList } from "@/service/namespace";
import { queryApplications } from '@/service';
import { useRouter } from 'next/navigation';

export default function ApplicationPage() {

  const {setHeaderContent} = useHeader();
  const [namespaces, setNamespaces] = useState<string[] | null>(null);
  const [namespaceValueEnum, setNamespaceValueEnum] = useState<Record<string, { text: string }>>({});
  const router = useRouter();

  useEffect(() => {
    setHeaderContent(
      <span>Application</span>
    )

    return () => {
      setHeaderContent('')
    }
  }, [setHeaderContent])

  useEffect(() => {
    fetchNamespaceList().then((data) => {
      setNamespaces(data)
      const valueEnum = data.reduce((acc, namespace) => {
        acc[namespace] = { text: namespace }
        return acc
      }, {} as Record<string, { text: string }>)
      setNamespaceValueEnum(valueEnum)
    })

    return () => {
      setNamespaces(null)
      setNamespaceValueEnum({});
    }
  }, []);

  if (!namespaces) {
    return <></>
  }

  return (
    <div className="p-3">
      <ProTable<ApplicationItem>
        options={false}
        rowKey="id"
        columns={[
          {
            title: 'Name',
            dataIndex: 'name',
            render: (_, record) =>
            <>
              <Button onClick={() => router.push(`/namespace/${record.namespace}/application/${record.name}/status`)}>{record.name}</Button>
            </>,
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
            title: 'Action',
            valueType: 'option',
            render: (_, record) => (
              <Space>
                <Button icon={<EditOutlined />} onClick={() => router.push(`/namespace/${record.namespace}/application/${record.name}`)}>Edit</Button>
                <Button icon={<SendOutlined />} onClick={() => router.push(`/namespace/${record.namespace}/application/${record.name}/deployment`)}>Deploy</Button>
                <Button icon={<InfoCircleOutlined />} onClick={() => router.push(`/namespace/${record.namespace}/application/${record.name}/status`)}>Status</Button>
                <Button icon={<DatabaseOutlined />} onClick={() => router.push(`/namespace/${record.namespace}/application/${record.name}/configmap`)}>Config</Button>
              </Space>
            ),
          },
        ]}
        request={async (params) => {
          const data = await queryApplications(params);
          return {
            data: data
          };
        }}
        toolBarRender={() => [
          <Button key="create" type="primary" href="/application/create">Create</Button>
        ]}
        cardBordered
      />
    </div>
  );
}