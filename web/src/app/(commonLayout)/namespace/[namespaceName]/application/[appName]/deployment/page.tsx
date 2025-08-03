'use client';

import { useHeader } from "@/context/header-context";
import { fetchApplicationDetail } from "@/service/application";
import { deployApplication } from "@/service/deployment";
import { ApplicationItem } from "@/types/application";
import { SendOutlined } from "@ant-design/icons";
import { Button, Card, Descriptions } from "antd";
import { useParams, useRouter } from "next/navigation";
import { useEffect, useState } from "react";

const DeploymentPage = () => {
  const { setHeaderContent } = useHeader()
  const params = useParams();
  const appName = params?.appName as string;
  const namespaceName = params?.namespaceName as string;
  const router = useRouter();

  const [application, setApplication] = useState<ApplicationItem | null>(null);

  const handleDeploy = () => {
    console.log('deploy')
    deployApplication(application!.namespace, application!.name).then((data) => {
      router.push(`/namespace/${namespaceName}/application/${appName}/pipeline/${data}`)
    })
  }

  useEffect(() => {
    setHeaderContent('Deployment')
    return () => {
      setHeaderContent('')
    }
  })

  useEffect(() => {
    fetchApplicationDetail(namespaceName, appName).then((application) => {
      setApplication(application)
    })
    return () => {
      setApplication(null)
    }
  }, [appName, namespaceName])

  if (!application) {
    return <></>
  }

  return (
    <div className="h-full">
      <Card>
        <Descriptions>
          <Descriptions.Item label="Application" span={'filled'}>{application?.name}</Descriptions.Item>
          <Descriptions.Item label="Namespace" span={'filled'}>{application?.namespace}</Descriptions.Item>
          <Descriptions.Item label="Repository" span={'filled'}>{application?.repository}</Descriptions.Item>
          <Descriptions.Item span={'filled'}>
            <Button 
              type="primary" 
              icon={<SendOutlined />}
              onClick={() => handleDeploy()}
            >
              Deploy
            </Button>
          </Descriptions.Item>
        </Descriptions>
      </Card>
      <Card>
        1
      </Card>
    </div>
  );
}

DeploymentPage.displayName = 'DeploymentPage';

export default DeploymentPage;