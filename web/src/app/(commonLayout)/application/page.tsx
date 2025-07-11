'use client';

import { ProTable } from '@ant-design/pro-components';
import { Button, Space } from 'antd';
import { ApplicationItem } from '@/types/application';
import { fetchApplicationList } from '@/service/application';
import Link from 'next/link';
import { DatabaseOutlined, EditOutlined, InfoCircleOutlined } from '@ant-design/icons';

export default () => (
  <ProTable<ApplicationItem>
    options={false}
    rowKey="id"
    columns={[
      // {
      //   title: 'Id',
      //   dataIndex: 'id'
      // },
      {
        title: 'Name',
        dataIndex: 'name',
        render: (_, record) => 
        <>
          <Link href={`/application/${record.name}/status`}>{record.name}</Link>
        </>,
      },
      {
        title: 'Namespace',
        dataIndex: 'namespace',
      },
      {
        title: 'Action',
        valueType: 'option',
        render: (_, record) => (
          <Space>
            <Button icon={<EditOutlined />} href={`/application/${record.name}`}>Edit</Button>
            <Button icon={<InfoCircleOutlined />} href={`/application/${record.name}/status`}>Status</Button>
            <Button icon={<DatabaseOutlined />} >Config</Button>
          </Space>
        ),
      },
    ]}
    params={{}}
    request={async () => {
      const data = await fetchApplicationList();
      return {
        data: data
      };
    }}
    pagination={{
      showSizeChanger: true,
    }}
    search={false}
  />
);