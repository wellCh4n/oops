import { createEnvironment, updateEnvironment } from "@/service/environment";
import { EnvironmentItem } from "@/types/environment";
import { Form, Input, Button } from "antd";

type EditorMode = "create" | "edit";

type EnvironmentEditorProps = {
  environment: EnvironmentItem;
  mode: EditorMode;
  onFinish: () => void;
}

const EnvironmentEditor: React.FC<EnvironmentEditorProps> = ({ environment, mode, onFinish }) => {
  return (
    <Form
      layout="vertical"
      initialValues={environment}
      onFinish={async (values) => {
        if (mode === "create") {
          createEnvironment(values).then(() => {
            onFinish();
          })
        } else {
          updateEnvironment(environment.id, values).then(() => {
            onFinish();
          })
        }
      }}
    >
      <Form.Item 
        label="Name" 
        name="name"
        required
        rules={[{ required: true, message: 'Please input name' }]}
      >
        <Input disabled={mode === "edit"} autoComplete="off" />
      </Form.Item>
      <Form.Item 
        label="API Server" 
        name="apiServerUrl" 
        required 
        rules={[{ required: true, message: 'Please input API Server' }]}
      >
        <Input autoComplete="off" />
      </Form.Item>
      <Form.Item 
        label="API Server Token" 
        name="apiServerToken" 
        required 
        rules={[{ required: true, message: 'Please input API Server Token' }]}
      >
        <Input.TextArea autoComplete="off" />
      </Form.Item>
      <Form.Item 
        label="Work Namespace" 
        name="workNamespace" 
        required 
        rules={[{ required: true, message: 'Please input Work Namespace' }]}
      >
        <Input autoComplete="off" />
      </Form.Item>
      <Form.Item 
        label="Build Storage Class" 
        name="buildStorageClass" 
        required 
        rules={[{ required: true, message: 'Please input Build Storage Class' }]}
      >
        <Input autoComplete="off" />
      </Form.Item>
      <Form.Item 
        label="Image Repository URL" 
        name="imageRepositoryUrl" 
        required 
        rules={[{ required: true, message: 'Please input Image Repository URL' }]}
      >
        <Input autoComplete="off" />
      </Form.Item>
      <Button type="primary" htmlType="submit">
        Save
      </Button>
    </Form>
  )
}

export default EnvironmentEditor;