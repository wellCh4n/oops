import { DownloadOutlined, PlusOutlined } from '@ant-design/icons';
import { Button, Card, Input, List, Space } from 'antd';
import React, { useEffect, useState } from 'react';
import request from 'umi-request';
import { App } from '../data';
import { ApplicationPipe } from './data';


const applicationPipelineUrl = '/oops/api/application/pipe/line';

const Pipeline: React.FC<Partial<App>> = (props) => {

  const[pipeline, setPipeline] = useState<ApplicationPipe[]>([]);

  useEffect(() => {
    if (props.id) {
      request.get(applicationPipelineUrl, {
        params: {
          id: props.id
        }
      }).then((res) => {
        if (res.success) {
          let data = res.data as ApplicationPipe[]
          setPipeline(data)
        }
      })
    }
  }, []);

  const add = () => {
  }

  return (
    <>
      <List<ApplicationPipe>
        dataSource={pipeline}
        grid={{ gutter: 16, column: 2 }}
        renderItem={(pipe) => (
          <List.Item>
            <Card title={`步骤${pipe.order} - ${pipe.params.name}`}>
              {Object.keys(pipe.params).map(key => {
                return <Input addonBefore={key} value={pipe.params[key]}></Input>
              })}
            </Card>
          </List.Item>
        )}
      />
      <Space wrap>
        <Button type='dashed' icon={<PlusOutlined />} onClick={add}></Button>
        <Button htmlType='submit' type='primary'>保存</Button>
      </Space>
    </>
  );
}

export default Pipeline;