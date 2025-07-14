'use client';

import { fetchNamespaceList } from "@/service/namespace";
import { ApplicationItem } from "@/types/application";
import { ProCard, ProForm, ProFormDigit, ProFormText } from "@ant-design/pro-components";
import Link from "next/link";
import React, { useEffect, useState }  from "react";

type EditorMode = "create" | "preview" | "edit";

interface ApplicationEditorProps {
  mode: EditorMode;
  application?: ApplicationItem
}

const ApplicationEditor: React.FC<ApplicationEditorProps> = ({ mode, application }) => {

  const editMode = mode === "edit";
  const previewMode = mode === "preview";
  const createMode = mode === "create";

  const [ namespaces, setNamespaces ] = useState<string[] | null>([]);

  if (createMode || editMode) {
    useEffect(() => {
      fetchNamespaceList().then((namespaces) => {
        setNamespaces(namespaces);
      })

      return () => {
        setNamespaces(null)
      }
    }, [ createMode, editMode ])
  }

  return (
    <div>
      <ProForm
          layout='horizontal'
          request={ async () => {
            return application
          }}
        >
          <ProCard
            title="Metadata"
            bordered
            extra={
              <Link href={`/namespace/${application?.namespace}/application/${application?.name}/status`}>Status</Link>
            }
          >
            { !createMode && (
              <ProForm.Group>
                <ProFormText name="id" label="Id" placeholder="Application Id" disabled width="md"/>
              </ProForm.Group>
            )}
            <ProForm.Group>
              <ProFormText name="name" label="Application" placeholder="Application Name" disabled={editMode || previewMode} width="md"/>
              
              { previewMode && (
                <ProFormText name="namespace" label="Namespace" placeholder="Namespace" disabled width="md"/>
              )}

              { createMode && (
                <ProFormText name="namespace" label="Namespace" placeholder="Namespace" width="md"/>
              )}
              
            </ProForm.Group>
          </ProCard>

          <ProCard
            title="Build"
            bordered
          >
            <ProForm.Group>
              <ProFormText name="repository" label="Repository" placeholder="Repository URL" disabled={previewMode} width="xl"/>
            </ProForm.Group>
            <ProForm.Group>
              <ProFormText name="buildImage" label="Build Image" placeholder="Build Image" disabled={previewMode} width="md"/>
              <ProFormText name="buildCommand" label="Build Command" placeholder="Build Command" disabled={previewMode} width="md"/>
              <ProFormText name="dockerFile" label="Docker File Path" placeholder="Docker File Path" disabled={previewMode} width="md"/>
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
  )
}

export default ApplicationEditor;