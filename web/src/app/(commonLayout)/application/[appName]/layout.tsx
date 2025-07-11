'use client'

import {type ReactNode, useEffect, useState, use} from "react";
import type { ApplicationItem } from "@/types/application";
import { fetchApplicationDetail } from "@/service/application";
import { ApplicationContext } from "@/context/application-context";
import { Skeleton} from "antd";

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
      <div className="h-full">
        {children}
      </div>
    </ApplicationContext.Provider>
  );
};

export default ApplicationLayout;