import { Button, Form, Input, InputNumber, Tabs, TabsProps } from 'antd';
import React, { useEffect, useState } from 'react';
import request from 'umi-request'
import { App } from '../data';
import { RouteComponentProps } from 'react-router';
import { Pipeline } from '../Pipeline';

const detailUrl = '/oops/api/application/detail'
const updateUrl = '/oops/api/application/update';
const createUrl = '/oops/api/application/create';

const ApplicationEdit: React.FC<RouteComponentProps> = (props) => {
  const[form] = Form.useForm();
  const[app, setApp] = useState<App>();
  const[isEdit, setIsEdit] = useState<boolean>(false);

  const fetchAppDetail = (pararms: object) => {
    request.get(detailUrl, {
      params: {...pararms}
    }).then((res) => {
      if (res.success) {
        let app = res.data as App;
        setApp(app);
        form.setFieldsValue({...app})
      }
    });
  };

  const saveAppDetail = (app: App) => {
    if(isEdit) {
      request.post(updateUrl, {data: app});
    } else {
      request.post(createUrl, {data: app});
    }
  }
  
  const tabItems: TabsProps['items'] = [
    {
      key: 'info',
      label: `基本信息`,
      children: <>
        <Form form={form} autoComplete='off' onFinish={saveAppDetail}>
          <Form.Item label='应用id' name='id' required hidden={!isEdit}>
            <Input disabled={isEdit}/>
          </Form.Item>
          <Form.Item label='应用名' name='appName' required>
            <Input disabled={isEdit}/>
          </Form.Item>
          <Form.Item label='应用描述' name='description'>
            <Input.TextArea/>
          </Form.Item>
          <Form.Item label='CPU请求核数' name='requestCoreCount' hidden required>
            <InputNumber addonAfter="核" />
          </Form.Item>
          <Form.Item label='CPU最大核数' name='limitCoreCount' hidden required>
            <InputNumber addonAfter="核" />
          </Form.Item>
          <Form.Item label='内存请求数' name='requestMemoryCount' hidden required>
            <InputNumber addonAfter="MB" />
          </Form.Item>
          <Form.Item label='内存最大数' name='limitMemoryCount' hidden required>
            <InputNumber addonAfter="MB" />
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
        <Pipeline meta={{flowId: props.match.params.id}}></Pipeline>
      </div>
    },
    {
      key: 'config',
      label: `应用配置`,
      children: <>
        <Form form={form} autoComplete='off' onFinish={saveAppDetail}>
          <Form.Item label='CPU请求核数' name='requestCoreCount' required>
            <InputNumber addonAfter="核" />
          </Form.Item>
          <Form.Item label='CPU最大核数' name='limitCoreCount' required>
            <InputNumber addonAfter="核" />
          </Form.Item>
          <Form.Item label='内存请求数' name='requestMemoryCount' required>
            <InputNumber addonAfter="MB" />
          </Form.Item>
          <Form.Item label='内存最大数' name='limitMemoryCount' required>
            <InputNumber addonAfter="MB" />
          </Form.Item>
          <Form.Item >
            <Button htmlType='submit' type='primary'>保存</Button>
          </Form.Item>
        </Form>
      </>
    }
  ]

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