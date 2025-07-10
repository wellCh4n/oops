'use client'

import {
  ProForm,
  ProFormText,
  ProFormDigit,
  ProCard,
} from "@ant-design/pro-components";
import { useApplicationContext } from "@/context/application-context";
import Link from "next/link";

export default () => {
  
  const application = useApplicationContext();

  return (
    <div>
       <Link href={`/application/${application?.name}/status`}>Status</Link>
      <ProForm
        layout='horizontal'
        request={ async () => {
          return application
        }}
      >
        <ProCard
          title="Metadata"
          bordered
        >
          <ProForm.Group>
            <ProFormText name="id" label="Id" placeholder="Application Id" disabled width="md"/>
          </ProForm.Group>
          <ProForm.Group>
            <ProFormText name="name" label="Application" placeholder="Application Name" disabled width="md"/>
            <ProFormText name="namespace" label="Namespace" placeholder="Namespace" disabled width="md"/>
          </ProForm.Group>
        </ProCard>

        <ProCard
          title="Build"
          bordered
        >
          <ProForm.Group>
            <ProFormText name="repository" label="Repository" placeholder="Repository URL" width="xl"/>
          </ProForm.Group>
          <ProForm.Group>
            <ProFormText name="buildImage" label="Build Image" placeholder="Build Image" width="md"/>
            <ProFormText name="buildCommand" label="Build Command" placeholder="Build Command" width="md"/>
            <ProFormText name="dockerFile" label="Docker File Path" placeholder="Docker File Path" width="md"/>
          </ProForm.Group>
        </ProCard>

        <ProCard
          title="Deployment"
          bordered
        >
          <ProForm.Group>
            <ProFormDigit name="replicas" label="Replicas" placeholder="Replicas" width="xs" min="0"/>
          </ProForm.Group>
        </ProCard>

      </ProForm>
    </div>
  );
}
