"use client"

import { useState, useEffect } from "react"
import {
  Form,
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/components/ui/form"
import { Input } from "@/components/ui/input"
import { Button } from "@/components/ui/button"
import { useForm, useFieldArray, useFormContext } from "react-hook-form"
import { zodResolver } from "@hookform/resolvers/zod"
import Editor from "@monaco-editor/react"
import { ApplicationBuildFormValues, applicationBuildSchema } from "../schema"
import { TabsContent } from "@/components/ui/tabs"
import { ApplicationBuildEnvironmentConfig, ApplicationBuildConfig, ApplicationEnvironment } from "@/lib/api/types"
import { updateApplicationBuildEnvConfigs, updateApplicationBuildConfig } from "@/lib/api/applications"
import { toast } from "sonner"
import { ApplicationEnvironmentSelector } from "./application-environment-selector"

interface ApplicationBuildInfoProps {
  initialBuildConfig?: ApplicationBuildConfig
  initialEnvConfigs?: ApplicationBuildEnvironmentConfig[]
  applicationId?: string
  applicationName?: string
  namespace?: string
}

export function ApplicationBuildInfo({
  initialBuildConfig,
  initialEnvConfigs = [],
  applicationId,
  applicationName,
  namespace,
}: ApplicationBuildInfoProps) {
  const form = useForm<ApplicationBuildFormValues>({
    resolver: zodResolver(applicationBuildSchema),
    defaultValues: {
      repository: initialBuildConfig?.repository || "",
      dockerFile: initialBuildConfig?.dockerFile || "Dockerfile",
      buildImage: initialBuildConfig?.buildImage || "",
      environmentConfigs: initialEnvConfigs as any,
    },
    mode: "onChange",
  })

  const { control } = form
  const { fields, replace } = useFieldArray({
    control,
    name: "environmentConfigs",
  })

  const [activeTab, setActiveTab] = useState<string | undefined>(undefined)
  const [isSaving, setIsSaving] = useState(false)

  const handleEnvironmentsLoaded = (envs: ApplicationEnvironment[]) => {
    // Sync form fields with fetched environments
    const currentConfigs = form.getValues("environmentConfigs") || []
    const newConfigs = envs.map((env) => {
      const existing = currentConfigs.find(
        (c) => c.environmentName === env.environmentName
      )
      return (
        existing || {
          environmentName: env.environmentName,
          buildCommand: "",
        }
      )
    })
    replace(newConfigs)

    // Set active tab if not set
    if (newConfigs.length > 0 && !activeTab) {
      setActiveTab(newConfigs[0].environmentName)
    }
  }

  // Initialize activeTab
  useEffect(() => {
    if (fields.length > 0 && !activeTab) {
      setActiveTab(fields[0].environmentName)
    }
  }, [fields, activeTab])

  const handleSave = async (data: ApplicationBuildFormValues) => {
    if (!applicationId || !applicationName || !namespace) {
      toast.error("请先保存应用基本信息")
      return
    }

    setIsSaving(true)
    try {
      // 1. Save global build config
      const buildConfigPayload: ApplicationBuildConfig = {
        repository: data.repository,
        dockerFile: data.dockerFile,
        buildImage: data.buildImage,
        namespace,
        applicationName,
      }
      await updateApplicationBuildConfig(namespace, applicationName, buildConfigPayload)

      // 2. Save environment configs
      const envConfigs = data.environmentConfigs.map(config => ({
          ...config,
          namespace,
          applicationName,
      })) as ApplicationBuildEnvironmentConfig[]
      await updateApplicationBuildEnvConfigs(namespace, applicationName, envConfigs)

      toast.success("构建配置保存成功")
    } catch (error) {
      console.error(error)
      toast.error("保存配置失败")
    } finally {
      setIsSaving(false)
    }
  }

  return (
    <Form {...form}>
      <form onSubmit={form.handleSubmit(handleSave)}>
        <div className="space-y-6">
          {/* Global Build Config Section */}
          <div className="space-y-4">
            <h3 className="text-lg font-medium">源码配置</h3>
            <FormField
              control={form.control}
              name="repository"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>代码仓库</FormLabel>
                  <FormControl>
                    <Input placeholder="输入代码仓库地址" {...field} />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />

            <div className="grid grid-cols-2 gap-4">
              <FormField
                control={form.control}
                name="dockerFile"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Dockerfile 路径</FormLabel>
                    <FormControl>
                      <Input placeholder="Dockerfile" {...field} />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />

              <FormField
                control={form.control}
                name="buildImage"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>构建镜像</FormLabel>
                    <FormControl>
                      <Input placeholder="输入构建镜像" {...field} />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
            </div>
          </div>

          <div className="border-t pt-6">
            <h3 className="text-lg font-medium mb-4">环境构建配置</h3>
            <div className="w-full">
              <ApplicationEnvironmentSelector
                namespace={namespace}
                applicationName={applicationName}
                value={activeTab}
                onValueChange={setActiveTab}
                onEnvironmentsLoaded={handleEnvironmentsLoaded}
                className="w-full"
              >
                {fields.map((field, index) => (
                  <TabsContent key={field.id} value={field.environmentName}>
                    <SingleEnvironmentConfig index={index} />
                  </TabsContent>
                ))}
              </ApplicationEnvironmentSelector>
            </div>
          </div>

          <div className="flex justify-end pt-4">
             <Button type="submit" disabled={isSaving}>
                {isSaving ? "保存中..." : "保存配置"}
             </Button>
          </div>
        </div>
      </form>
    </Form>
  )
}

interface SingleEnvironmentConfigProps {
  index: number
}

function SingleEnvironmentConfig({ index }: SingleEnvironmentConfigProps) {
  const { control } = useFormContext<ApplicationBuildFormValues>()

  return (
    <div className="space-y-4">
      <FormField
        control={control}
        name={`environmentConfigs.${index}.buildCommand`}
        render={({ field }) => (
          <FormItem>
            <FormLabel>构建命令</FormLabel>
            <FormControl>
              <div className="border rounded-md overflow-hidden">
                <div className="bg-muted px-3 py-1 text-xs text-muted-foreground border-b flex items-center">
                  <span>shell</span>
                </div>
                <div className="h-[200px]">
                  <Editor
                    height="100%"
                    defaultLanguage="shell"
                    theme="vs-dark"
                    value={field.value}
                    onChange={field.onChange}
                    options={{
                      minimap: { enabled: false },
                      lineNumbers: "on",
                      scrollBeyondLastLine: false,
                      automaticLayout: true,
                      padding: { top: 10 },
                    }}
                  />
                </div>
              </div>
            </FormControl>
            <FormMessage />
          </FormItem>
        )}
      />
    </div>
  )
}
