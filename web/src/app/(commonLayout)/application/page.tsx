'use client';

import { ProTable } from '@ant-design/pro-components';
import { Button, Space } from 'antd';
import { ApplicationItem } from '@/types/application';
import { fetchApplicationList } from '@/service/application';
import Link from 'next/link';
import { DatabaseOutlined, EditOutlined, InfoCircleOutlined } from '@ant-design/icons';
import { useEffect } from 'react';
import { useHeader } from '@/context/header-context';
import {useParams} from "next/navigation";

export default function ApplicationPage() {

  const {setHeaderContent} = useHeader()

  useEffect(() => {
    setHeaderContent(
      <span>Application</span>
    )

    return () => {
      setHeaderContent('')
    }
  }, [setHeaderContent])

  return (
    <div className="p-3">
      <ProTable<ApplicationItem>
        options={false}
        rowKey="id"
        columns={[
          {
            title: 'Id',
            dataIndex: 'id',
            search: false
          },
          {
            title: 'Name',
            dataIndex: 'name',
            render: (_, record) =>
            <>
              <Link href={`/namespace/${record.namespace}/application/${record.name}/status`}>{record.name}</Link>
            </>,
            search: true
          },
          {
            title: 'Namespace',
            dataIndex: 'namespace',
            search: true
          },
          {
            title: 'Action',
            valueType: 'option',
            render: (_, record) => (
              <Space>
                <Button icon={<EditOutlined />} href={`/namespace/${record.namespace}/application/${record.name}`}>Edit</Button>
                <Button icon={<InfoCircleOutlined />} href={`/namespace/${record.namespace}/application/${record.name}/status`}>Status</Button>
                <Button icon={<DatabaseOutlined />} >Config</Button>
              </Space>
            ),
          },
        ]}
        request={async (params, sort, filter) => {
          console.log(params);
          const data = await fetchApplicationList();
          return {
            data: data
          };
        }}
        pagination
        cardBordered
        search={true}
      />
    </div>
  );
}

// export default () => (
  
// );