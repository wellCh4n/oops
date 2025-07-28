"use client"

import { useHeader } from "@/context/header-context";
import { fetchConfigMap, updateConfigMap } from "@/service/configmap";
import { ProForm, ProFormCheckbox, ProFormGroup, ProFormList, ProFormSelect, ProFormText, ProFormTextArea } from "@ant-design/pro-components";
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
    <div className="p-3">
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
              width="xl"
              label="Value"
              fieldProps={{
                rows: 4,
              }}
            />
            <ProFormCheckbox
              name="mountAsPath"
              label="Mount as path"
              width="sm"
            />
          </ProFormGroup>
        </ProFormList>
      </ProForm>
    </div>
  );
};

export default ConfigMapPage;