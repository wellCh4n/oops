"use client";

import { useApplicationContext } from "@/context/application-context";
import { Button, List, Skeleton, Space, Table, TableProps, Tag } from "antd";
import { useEffect, useState } from "react";
import { fetchApplicationStatus, restartApplication } from "@/service/application";
import { ApplicationPodItem } from "@/types/application";
import { CodeOutlined, FileTextOutlined, ReloadOutlined } from "@ant-design/icons";

export default () => {

  const application = useApplicationContext();

  const [applicationPods, setApplicationPods] = useState<ApplicationPodItem[] | null>(null);

  const loadApplicationStauts = () => {
    fetchApplicationStatus(application!.name).then((data) => {
      setApplicationPods(data)
    })
  };

  useEffect(() => {
    if(!application) return;
    
    loadApplicationStauts();
    
    const interval = setInterval(loadApplicationStauts, 5000);
    
    return () => clearInterval(interval);
  }, [application?.name])

  if(!applicationPods) {
    return <Skeleton active/>
  }

  const colmus: TableProps<ApplicationPodItem>['columns'] = [
    {
      title: 'Name',
      dataIndex: 'name',
      key: 'name'
    },
    {
      title: 'IP',
      "dataIndex": 'podIP',
      key: 'ip'
    },
    {
      title: 'Images',
      key: 'images',
      width: 300,
      render: (_, records) => (
        <div className="flex flex-wrap gap-1 max-w-full">
          {records.image.map((image) => (
            <Tag key={image}>
              {image}
            </Tag>
          ))}
        </div>
      )
    },
    {
      title: 'Status',
      dataIndex: 'status',
      key: 'status',
      render: (text) => <Tag color={text === 'Running' ? 'green' : 'red'}>{text}</Tag>,
    },
    {
      title: 'Action',
      key: 'action',
      render: (_, record) => (
        <Space>
          <Button icon={<FileTextOutlined />} href={`/application/${application!.name}/pod/${record.name}/log`}>Logs</Button>
          <Button icon={<CodeOutlined />} href={`/application/${application!.name}/pod/${record.name}/terminal`}>Terminal</Button>
          <Button icon={<ReloadOutlined />} danger onClick={() => {
            restartApplication(application!.name, record.name)
          }}>Restart</Button>
        </Space>
      )
    }
  ]

  return (
    <div >
      <Table scroll={{ x: 'max-content' }} columns={colmus} dataSource={applicationPods} />
    </div>
  );
}