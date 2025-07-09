'use client';

import { ProTable } from '@ant-design/pro-components';
import { Button, Space, Tag } from 'antd';
import request from 'umi-request';

type ApplicationItem = {
  id: string;
  name: string;
  namespace: string;
};

async function myQuery() {
  // 示例请求，可替换为你自己的 API 请求逻辑
  const res = await request<{
    data: ApplicationItem[];
    // total: number;
    success: boolean;
  }>('http://localhost:8080/api/namespaces/default/applications', {
    method: 'GET',
    // params,
  });

  return res;
}

export default () => (
  <ProTable<ApplicationItem>
    rowKey="id"
    columns={[
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
            <Button type="link">查看</Button>
            <Button type="link" danger>
              删除
            </Button>
          </Space>
        ),
      },
    ]}
    params={{}}
    request={async (params) => {
      const msg = await myQuery();
      return {
        data: msg.data,
        success: msg.success,
        // total: msg.total,
      };
    }}
    pagination={{
      showSizeChanger: true,
    }}
    search={false}
  />
);