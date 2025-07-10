"use client";

import { useApplicationContext } from "@/context/application-context";
import { Button, List } from "antd";
import { useEffect, useState } from "react";
import { fetchApplicationStatus } from "@/service/application";
import { ApplicationPodItem } from "@/types/application";

export default () => {

  const application = useApplicationContext();

  const [applicationPods, setApplicationPods] = useState<ApplicationPodItem[]>([]);

  useEffect(() => {
    fetchApplicationStatus(application!.name).then((data) => {
      setApplicationPods(data)
    })
  }, [application?.name])

  return (
    <>
      <List 
        dataSource={applicationPods}
        renderItem={(item) => 
          <List.Item
            actions={[
              <Button>Logs</Button>,
              <Button href={`/application/${application?.name}/pod/${item.name}/terminal`}>Terminal</Button>,
              <Button danger>Restart</Button>,
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