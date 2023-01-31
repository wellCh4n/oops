import { Card, List } from 'antd';
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

  return (
    <>
      <List<ApplicationPipe>
        dataSource={pipeline}
        grid={{ gutter: 16, column: 4 }}
        renderItem={(pipe) => (
          <List.Item>
            <Card title={`步骤${pipe.order} - ${pipe.pipeName}`}>{JSON.stringify(pipe.params)}</Card>
          </List.Item>
        )}
      />
    </>
  );
}

export default Pipeline;