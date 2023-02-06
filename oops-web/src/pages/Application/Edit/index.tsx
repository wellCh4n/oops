import { Button, Form, Input, Tabs, TabsProps } from 'antd';
import React, { useEffect, useState } from 'react';
import request from 'umi-request'
import { App } from '../data';
import { RouteComponentProps } from 'react-router';
import { Pipeline } from '../Pipeline';

const deatilUrl = '/oops/api/application/detail'

const ApplicationEdit: React.FC<RouteComponentProps> = (props) => {
  const[form] = Form.useForm();
  const[app, setApp] = useState<App>();
  const[isEdit, setIsEdit] = useState<boolean>(false);
  
  const tabItems: TabsProps['items'] = [
    {
      key: 'info',
      label: `基本信息`,
      children: <>
        <Form form={form} autoComplete='off'>
          <Form.Item label='应用id' name='id' required hidden={!isEdit}>
            <Input disabled={isEdit}/>
          </Form.Item>
          <Form.Item label='应用名' name='appName' required>
            <Input disabled={isEdit}/>
          </Form.Item>
          <Form.Item label='应用描述' name='description'>
            <Input.TextArea/>
          </Form.Item>
          <Form.Item >
            <Button htmlType='submit' type='primary'>保存</Button>
          </Form.Item>
        </Form>
      </>,
    },
    {
      key: 'pipeline',
      label: `流水线`,
      children: <div style={{height: '666px'}}>
        <Pipeline meta={{flowId: '1'}}></Pipeline>
      </div>
    },
    {
      key: 'config',
      label: `应用配置`,
      children: ``
    }
  ]

  const fetchAppDetail = (pararms: object) => {
    request.get(deatilUrl, {
      params: {...pararms}
    }).then((res) => {
      if (res.success) {
        let app = res.data as App;
        setApp(app);
        form.setFieldsValue({...app})
      }
    });
  };

  useEffect(() => {
    if (props.location.pathname !== '/application/add') {
      fetchAppDetail(props.match.params);
      setIsEdit(true);
    }
  }, []);

  return (
    <Tabs
      items={tabItems}
    />
  )
}

export default ApplicationEdit; 