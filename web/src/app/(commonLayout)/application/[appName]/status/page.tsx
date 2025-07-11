"use client";

import { useApplicationContext } from "@/context/application-context";
import { Button, List, Skeleton } from "antd";
import { useEffect, useState } from "react";
import { fetchApplicationStatus, restartApplication } from "@/service/application";
import { ApplicationPodItem } from "@/types/application";

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

  return (
    <>
      <List 
        dataSource={applicationPods}
        renderItem={(item) => 
          <List.Item
            actions={[
              <Button>Logs</Button>,
              <Button href={`/application/${application!.name}/pod/${item.name}/terminal`}>Terminal</Button>,
              <Button onClick={() => {
                restartApplication(application!.name, item.name)
              }} danger>Restart</Button>,
            ]}
          >
            <List.Item.Meta
              title={item.name}
              description={item.status}
            />
          </List.Item>
        }
      />
    </>
  );
}