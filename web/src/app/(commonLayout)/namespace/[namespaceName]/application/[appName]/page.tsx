'use client'

import {
  ProForm,
  ProFormText,
  ProFormDigit,
  ProCard,
} from "@ant-design/pro-components";
import { useApplicationContext } from "@/context/application-context";
import Link from "next/link";
import {useParams} from "next/navigation";
import ApplicationEditor from "@/component/ApplicationEditor";

export default () => {
  
  const application = useApplicationContext();
  const { namespaceName, appName } = useParams()

  if (!application) {
    return <></>
  }

  return (
    <div>
      <ApplicationEditor mode="edit" application={application} />
    </div>
  );
}
