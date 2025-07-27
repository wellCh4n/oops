'use client';

import { createApplication, updateApplication } from "@/service/application";
import { createApplicationBuildStorage, deleteApplicationBuildStorage, fetchBuildStorageList } from "@/service/build";
import { fetchNamespaceList } from "@/service/namespace";
import { ApplicationItem } from "@/types/application";
import { BuildStorageItem } from "@/types/build";
import { AppstoreAddOutlined } from "@ant-design/icons";
import { ProCard, ProForm, ProFormDigit, ProFormGroup, ProFormList, ProFormSelect, ProFormText } from "@ant-design/pro-components";
import { Button, Form, FormInstance, Input, Modal, Skeleton, Tag } from "antd";
import Link from "next/link";
import { useRouter } from "next/navigation";
import React, { useEffect, useRef, useState }  from "react";

type EditorMode = "create" | "preview" | "edit";

interface ApplicationEditorProps {
  mode: EditorMode;
  application?: Partial<ApplicationItem>
}

const ApplicationEditor: React.FC<ApplicationEditorProps> = ({ mode, application }) => {

  const editMode = mode === "edit";
  const previewMode = mode === "preview";
  const createMode = mode === "create";

  const [ namespaces, setNamespaces ] = useState<string[] | null>([]);
  const [ form ] = ProForm.useForm();
  const router = useRouter();

  const buildStorageAddFormRef = useRef<FormInstance>(null);
  const [ buildStorageAddModalOpen, setBuildStorageAddModalOpen ] = useState(false);
  const [ buildStorages, setBuildStorages ] = useState<BuildStorageItem[]>([]);

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

  useEffect(() => {
    if (editMode || previewMode) {
      fetchBuildStorageList(application?.namespace || '', application?.name || '').then((buildStorages) => {
        setBuildStorages(buildStorages);
      })
    }
  }, [editMode, previewMode, form, application?.namespace, application?.name])

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

  const handleBuildStorageAdd = async (values: BuildStorageItem) => {
    createApplicationBuildStorage(application?.namespace || '', application?.name || '', values).then((_) => {
      setBuildStorageAddModalOpen(false);
      setBuildStorages([...buildStorages, values]);
    })
  }

  return (
    <div className="p-3">

      <Modal
        title="Add Build Storage"
        open={buildStorageAddModalOpen}
        onCancel={() => setBuildStorageAddModalOpen(false)}
        onOk={async () => {
          buildStorageAddFormRef.current?.validateFields().then((values) => {
            handleBuildStorageAdd(values);
          }).catch((_) => {})
        }}
      >
        <Form
          ref={buildStorageAddFormRef}
          preserve={false}
          layout="vertical"
        >
          <Form.Item label="Path" name="path" required rules={[{ required: true, message: "Path is required" }]}>
            <Input autoComplete="off" />
          </Form.Item>
          <Form.Item label="Capacity" name="capacity" required rules={[{ required: true, message: "Capacity is required" }]}>
            <Input autoComplete="off" />
          </Form.Item>
        </Form>
      </Modal>

      <ProForm
          form={form}
          request={ async () => {
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
            <div>
              <div>
                <span className="pr-1">Build Storage</span>
                <Button 
                  variant="outlined" 
                  icon={<AppstoreAddOutlined />}
                  size="small" 
                  onClick={() => setBuildStorageAddModalOpen(true)}
                >
                  Add
                </Button>
              </div>
              <span className="inline-block p-1 mt-1 border border-solid border-gray-300 rounded-md">
              { buildStorages.map((buildStorage) => {
                return <>
                  <Tag 
                    key={buildStorage.path} 
                    closable={!previewMode}
                    onClose={async () => {
                      deleteApplicationBuildStorage(application?.namespace || '', application?.name || '', buildStorage).then((_) => {
                        setBuildStorages(buildStorages.filter((item) => item.path !== buildStorage.path));
                      })
                    }}
                  >
                    {buildStorage.path} - {buildStorage.capacity}
                  </Tag>
                </>
              }) }
            </span>
            </div>
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