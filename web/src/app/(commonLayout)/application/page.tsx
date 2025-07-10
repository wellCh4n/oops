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
        title: '应用ID',
        dataIndex: 'id'
      },
      {
        title: '应用名称',
        dataIndex: 'name',
      },
      {
        title: '命名空间',
        dataIndex: 'namespace',
      },
      {
        title: '操作',
        valueType: 'option',
        render: (_, record) => (
          <Space>
            <Button type="link" href={`/application/${record.name}`}>编辑</Button>
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