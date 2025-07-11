'use client'

import {type ReactNode, useEffect, useState, use} from "react";
import type { ApplicationItem } from "@/types/application";
import { fetchApplicationDetail } from "@/service/application";
import { ApplicationContext } from "@/context/application-context";
import {Breadcrumb, Card, Divider, Skeleton} from "antd";
import Link from "next/link";

const ApplicationLayout = ({ children, params }: { children: ReactNode, params: Promise<{ appName: string }> }) => {

  const [application, setApplication] = useState<ApplicationItem>();
  const requestParams = use(params);

  useEffect(() => {
    fetchApplicationDetail(requestParams.appName).then((data) => {
      setApplication(data);
    })
  }, [requestParams.appName]);

  if (!application) {
    return <Skeleton active/>;
  }

  return (
    <ApplicationContext.Provider value={application}>
      <Breadcrumb
        separator=">"
        items={[
          {
            title: <Link href={`/application`}>Application</Link>,
          },
          {
            title: application.name,
          }
        ]}
      />
      <Card>
        {children}
      </Card>
    </ApplicationContext.Provider>
  );
};

export default ApplicationLayout;