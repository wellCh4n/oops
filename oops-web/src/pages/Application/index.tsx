import { PageContainer, ProTable, ProColumns } from '@ant-design/pro-components';
import React from 'react';
import request from 'umi-request';
import type { App } from './data';
// import { FormattedMessage, useIntl } from 'umi';
// import styles from './Welcome.less';

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
      <a>编辑</a>,
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
          return request.post('/oops/api/application/page', {
            data: {
              ...params
            }
          })
        }}
      />
    </PageContainer>
  );
};

export default Application;
