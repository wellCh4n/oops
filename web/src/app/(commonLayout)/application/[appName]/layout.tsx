'use client'

import {type ReactNode, useEffect, useState, use} from "react";
import type { ApplicationItem } from "@/types/application";
import { fetchApplicationDetail } from "@/service/application";
import { ApplicationContext } from "@/context/application-context";
import {Breadcrumb, Divider, Skeleton} from "antd";
import Link from "next/link";

export const ApplicationLayout = ({ children, params }: { children: ReactNode, params: Promise<{ appName: string }> }) => {

  const [application, setApplication] = useState<ApplicationItem>();
  const requestParams = use(params);

  useEffect(() => {
    fetchApplicationDetail(requestParams.appName).then((data) => {
      setApplication(data);
    })
  }, [requestParams.appName]);

  if (!application) {
    return <Skeleton />;
  }

  return (
    <ApplicationContext.Provider value={application}>
      <Breadcrumb
        items={[
          {
            title: <Link href={`/application`}>Application</Link>,
          },
          {
            title: application.name,
          }
        ]}
      />
      <Divider type={'horizontal'} />
      {children}
    </ApplicationContext.Provider>
  );
};

export default ApplicationLayout;