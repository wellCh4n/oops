"use client"

import { useHeader } from "@/context/header-context";
import { fetchConfigMap, updateConfigMap } from "@/service/configmap";
import { ProForm, ProFormGroup, ProFormList, ProFormTextArea } from "@ant-design/pro-components";
import { useParams } from "next/navigation";
import { useEffect } from "react";

const ConfigMapPage = () => {

  const params = useParams();
  const appName = params?.appName as string;
  const namespaceName = params?.namespaceName as string;

  const {setHeaderContent} = useHeader();

  useEffect(() => {
    setHeaderContent(
      <span>ConfigMap</span>
    )

    return () => {
      setHeaderContent('')
    }
  }, [setHeaderContent])

  return (
    <div>
      <ProForm
        request={async () => {
          const configMap = await fetchConfigMap(namespaceName, appName);
          return { configmap: configMap };
        }}
        onFinish={async (values) => {
          updateConfigMap(namespaceName, appName, values.configmap)
        }}
      >
        <ProFormList name="configmap">
          <ProFormGroup>
            <ProFormTextArea
              name="key"
              width="md"
              label="Key"
            />
            <ProFormTextArea
              name="value"
              width="md"
              label="Value"
              fieldProps={{
                rows: 4,
              }}
            />
            <ProFormTextArea
              name="mountPath"
              label="Mount Path"
              width="md"
            />
          </ProFormGroup>
        </ProFormList>
      </ProForm>
    </div>
  );
};

export default ConfigMapPage;