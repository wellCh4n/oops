'use client';

import { createApplication, updateApplication } from "@/service/application";
import { fetchNamespaceList } from "@/service/namespace";
import { ApplicationDetailItem, ApplicationItem } from "@/types/application";
import { ProCard, ProForm, ProFormDigit, ProFormGroup, ProFormList, ProFormSelect, ProFormText } from "@ant-design/pro-components";
import { Skeleton } from "antd";
import Link from "next/link";
import { useRouter } from "next/navigation";
import React, { useEffect, useState }  from "react";

type EditorMode = "create" | "preview" | "edit";

interface ApplicationEditorProps {
  mode: EditorMode;
  application?: Partial<ApplicationDetailItem>
}

const ApplicationEditor: React.FC<ApplicationEditorProps> = ({ mode, application }) => {

  const editMode = mode === "edit";
  const previewMode = mode === "preview";
  const createMode = mode === "create";

  const [ namespaces, setNamespaces ] = useState<string[] | null>([]);
  const [ form ] = ProForm.useForm();
  const router = useRouter();

  const [ existsBuildStorage, setExistsBuildStorage ] = useState<string[]>([]);

    useEffect(() => {
      if (createMode || editMode) {
        fetchNamespaceList().then((namespaces) => {
          setNamespaces(namespaces);
          if (createMode && namespaces && namespaces.length > 0) {
            form.setFieldsValue({ namespace: namespaces[0] });
          }
        })
      }

      return () => {
        setNamespaces(null)
      }
    }, [ createMode, editMode, form ])

  if (!namespaces) {
    return <Skeleton active />
  }

  const handlerCreate = (application: ApplicationItem) => {
    createApplication(application.namespace, application).then(() => {
      router.push(`/namespace/${application.namespace}/application/${application.name}`)
    });
  }

  const handleUpdate = (application: ApplicationItem) => {
    updateApplication(application.namespace, application)
  }

  return (
    <div className="p-3">
      <ProForm
          form={form}
          request={ async () => {
            const existsBuildStorage = application?.buildStorages?.map((storage) => storage.path) || [];
            setExistsBuildStorage(existsBuildStorage);
            return application
          }}
          onFinish={async (application) => {
            console.log(application)
            if (createMode) {
              handlerCreate(application);
            } else {
              handleUpdate(application);
            }
          }}
        >
          <ProCard
            title="Metadata"
            bordered
            extra={ !createMode && 
              <Link href={`/namespace/${application?.namespace}/application/${application?.name}/status`}>Status</Link>
            }
          >
            { !createMode && (
              <ProForm.Group>
                <ProFormText name="id" label="Id" placeholder="Application Id" disabled width="md"/>
              </ProForm.Group>
            )}
            <ProForm.Group>
              <ProFormText 
                name="name" 
                label="Application" 
                placeholder="Application Name" 
                required 
                disabled={editMode || previewMode} width="md"
                rules={[{ required: true, message: "Application Name is required" }]}
              />
              
              { (previewMode || editMode) && (
                <ProFormText 
                  name="namespace" 
                  label="Namespace" 
                  placeholder="Namespace" 
                  required 
                  disabled
                  width="md"
                  rules={[{ required: true, message: "Namespace is required" }]}
                />
              )}

              { createMode && namespaces && (
                <ProFormSelect 
                  name="namespace" 
                  label="Namespace" 
                  placeholder="Namespace" 
                  required
                  options={
                    namespaces.map((namespace) => ({
                      value: namespace,
                      label: namespace,
                    }))
                  }
                  width="md"
                  rules={[{ required: true, message: "Namespace is required" }]}
                />
              )}
              
            </ProForm.Group>
          </ProCard>

          <ProCard
            title="Build"
            bordered
          >
            <ProForm.Group>
              <ProFormText 
                name="repository" 
                label="Repository" 
                placeholder="Repository URL" 
                required 
                disabled={previewMode} 
                width="xl"
                rules={[{ required: true, message: "Repository is required" }]}
              />
            </ProForm.Group>
            <ProForm.Group>
              <ProFormText name="buildImage" label="Build Image" placeholder="Build Image" disabled={previewMode} width="md"/>
              <ProFormText name="buildCommand" label="Build Command" placeholder="Build Command" disabled={previewMode} width="md"/>
              <ProFormText 
                name="dockerFile" 
                label="Docker File Path" 
                placeholder="Docker File Path" 
                required 
                disabled={previewMode} 
                width="md"
                rules={[{ required: true, message: "Docker File Path is required" }]}
              />
            </ProForm.Group>
            <ProFormList name="buildStorages">
              <ProFormGroup>
                <ProFormText
                  name="path"
                  label="Path"
                />
                <ProFormText
                  name="volume"
                  label="Volume"
                />
              </ProFormGroup>
            </ProFormList>
          </ProCard>

          <ProCard
            title="Deployment"
            bordered
          >
            <ProForm.Group>
              <ProFormDigit 
                name="replicas" 
                label="Replicas" 
                placeholder="Replicas" 
                required 
                width="xs" 
                min="0"
                rules={[{ required: true, message: "Replicas is required" }]}
              />
            </ProForm.Group>
          </ProCard>
        </ProForm>
    </div>
  )
}

export default ApplicationEditor;