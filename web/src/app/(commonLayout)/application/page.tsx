'use client';

import { ProTable } from '@ant-design/pro-components';
import { Button, Space } from 'antd';
import { ApplicationItem } from '@/types/application';
import { fetchApplicationList } from '@/service/application';

export default () => (
  <ProTable<ApplicationItem>
    rowKey="id"
    columns={[
      {
        title: 'Id',
        dataIndex: 'id'
      },
      {
        title: 'Application Name',
        dataIndex: 'name',
      },
      {
        title: 'Namespace',
        dataIndex: 'namespace',
      },
      {
        title: 'Operation',
        valueType: 'option',
        render: (_, record) => (
          <Space>
            <Button type="link" href={`/application/${record.name}`}>Edit</Button>
            <Button type="link" href={`/application/${record.name}/status`}>Status</Button>
            {/*<Button type="link" danger>删除</Button>*/}
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