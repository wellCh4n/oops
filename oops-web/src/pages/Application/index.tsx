import { PageContainer, ProTable, ProColumns } from '@ant-design/pro-components';
import React from 'react';
import request from 'umi-request';
import type { App } from './data';
import { FormattedMessage, Link, useIntl } from 'umi';
import { Button } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
// import styles from './Welcome.less';

const pageUrl = '/oops/api/application/page';

const columns: ProColumns<App>[] = [
  {
    title: 'id',
    dataIndex: 'id',
    hideInSearch: true,
  },
  {
    title: '应用名',
    dataIndex: 'appName',
  },
  {
    title: '命名空间',
    dataIndex: 'namespace',
  },
  {
    title: '操作',
    valueType: 'option',
    render: (text, record, _, action) => [
      <Link to={`/application/edit/${record.id}`}>编辑</Link>,
      <a>发布</a>
    ]
  }
]

const Application: React.FC = () => {
  return (
    <PageContainer>
      <ProTable<App>
        columns={columns}
        pagination={{
          defaultPageSize: 10,
          showSizeChanger: true
        }}
        request={async (params = {}, sorter, filter) => {
          return request.post(pageUrl, {
            data: {
              ...params
            }
          })
        }}
        toolBarRender={() => [
          <Link to={`/application/add`}>
            <Button icon={<PlusOutlined />} type='primary'>创建应用</Button>
          </Link>
        ]}
      />
    </PageContainer>
  );
};

export default Application;
