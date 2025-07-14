"use client";

import { useApplicationContext } from "@/context/application-context";
import { useHeader } from "@/context/header-context";
import {Button, Card, Skeleton, Space, Table, TableProps, Tag} from "antd";
import { useCallback, useEffect, useState } from "react";
import { fetchApplicationStatus, restartApplication } from "@/service/application";
import { ApplicationPodItem } from "@/types/application";
import { CodeOutlined, FileTextOutlined, ReloadOutlined } from "@ant-design/icons";
import {useParams} from "next/navigation";

const ApplicationStatusPage = () => {
  const application = useApplicationContext();
  const { setHeaderContent } = useHeader();
  const [applicationPods, setApplicationPods] = useState<ApplicationPodItem[] | null>(null);
  const { namespaceName } =  useParams();

  useEffect(() => {
    if (application) {
      setHeaderContent(
        `Application Status: ${application.name}`
      );
    }
    
    return () => {
      setHeaderContent('');
    };
  }, [application, setHeaderContent]);

  const loadApplicationStatus = useCallback(() => {
    fetchApplicationStatus(application!.name).then((data) => {
      setApplicationPods(data)
    })
  }, [application]);

  useEffect(() => {
    if(!application) return;
    
    loadApplicationStatus();

    const interval = setInterval(loadApplicationStatus, 5000);
    
    return () => clearInterval(interval);
  }, [application?.name, application, loadApplicationStatus])

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
        <div className="flex flex-wrap gap-1">
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
          <Button
            icon={<FileTextOutlined />}
            href={`/namespace/${namespaceName}/application/${application!.name}/pod/${record.name}/log`}
          >
            Logs
          </Button>
          <Button
            icon={<CodeOutlined />}
            href={`/namespace/${namespaceName}/application/${application!.name}/pod/${record.name}/terminal`}>
            Terminal
          </Button>
          <Button
            icon={<ReloadOutlined />}
            danger
            onClick={() => restartApplication(application!.name, record.name)}
          >
            Restart
          </Button>
        </Space>
      )
    }
  ]

  return (
    <div >
      <Card>
        <Table columns={colmus} dataSource={applicationPods} pagination={false} />
      </Card>

    </div>
  );
}

export default ApplicationStatusPage;