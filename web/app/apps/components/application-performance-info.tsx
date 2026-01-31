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
import { ApplicationPerformanceEnvFormValues, applicationPerformanceEnvSchema } from "../schema"
import { TabsContent } from "@/components/ui/tabs"
import { ApplicationPerformanceEnvironmentConfig, ApplicationEnvironment } from "@/lib/api/types"
import { updateApplicationPerformanceEnvConfigs } from "@/lib/api/applications"
import { toast } from "sonner"
import { ApplicationEnvironmentSelector } from "./application-environment-selector"

interface ApplicationPerformanceInfoProps {
  initialEnvConfigs?: ApplicationPerformanceEnvironmentConfig[]
  applicationId?: string
  applicationName?: string
  namespace?: string
}

export function ApplicationPerformanceInfo({
  initialEnvConfigs = [],
  applicationId,
  applicationName,
  namespace,
}: ApplicationPerformanceInfoProps) {
  const form = useForm<ApplicationPerformanceEnvFormValues>({
    resolver: zodResolver(applicationPerformanceEnvSchema),
    defaultValues: {
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
          replicas: 0,
          cpuRequest: "0.1",
          cpuLimit: "1",
          memoryRequest: "128",
          memoryLimit: "512",
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

  const handleSave = async (data: ApplicationPerformanceEnvFormValues) => {
    if (!applicationId || !applicationName || !namespace) {
      toast.error("请先保存应用基本信息")
      return
    }

    const configs = data.environmentConfigs.map(config => ({
        ...config,
        namespace,
        applicationName,
    })) as ApplicationPerformanceEnvironmentConfig[]
    
    setIsSaving(true)
    try {
      await updateApplicationPerformanceEnvConfigs(namespace, applicationName, configs)
      toast.success("环境配置保存成功")
    } catch (error) {
      console.error(error)
      toast.error("保存环境配置失败")
    } finally {
      setIsSaving(false)
    }
  }

  return (
    <Form {...form}>
      <form onSubmit={form.handleSubmit(handleSave)} className="space-y-6">
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
                <SingleEnvironmentConfig
                  index={index}
                />
              </TabsContent>
            ))}
          </ApplicationEnvironmentSelector>
        </div>
        <div className="flex justify-end">
          <Button type="submit" disabled={isSaving}>
            {isSaving ? "保存中..." : "保存配置"}
          </Button>
        </div>
      </form>
    </Form>
  )
}

interface SingleEnvironmentConfigProps {
  index: number
}

function SingleEnvironmentConfig({ index }: SingleEnvironmentConfigProps) {
  const { control } = useFormContext<ApplicationPerformanceEnvFormValues>()

  return (
    <div className="space-y-4">
      <FormField
        control={control}
        name={`environmentConfigs.${index}.replicas`}
        render={({ field }) => (
          <FormItem>
            <FormLabel>副本数</FormLabel>
            <FormControl>
              <Input 
                type="number" 
                {...field}
                value={field.value ?? ''}
                onChange={e => {
                  const val = e.target.valueAsNumber
                  field.onChange(isNaN(val) ? undefined : val)
                }}
                autoComplete="off"
              />
            </FormControl>
            <FormMessage />
          </FormItem>
        )}
      />
      <div className="grid grid-cols-2 gap-4">
        <FormField
          control={control}
          name={`environmentConfigs.${index}.cpuRequest`}
          render={({ field }) => (
            <FormItem>
              <FormLabel>CPU 请求 (core)</FormLabel>
              <FormControl>
                <Input 
                  placeholder="例如 0.1"
                  {...field}
                  autoComplete="off"
                />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />
        <FormField
          control={control}
          name={`environmentConfigs.${index}.cpuLimit`}
          render={({ field }) => (
            <FormItem>
              <FormLabel>CPU 限制 (core)</FormLabel>
              <FormControl>
                <Input 
                  placeholder="例如 1"
                  {...field}
                  autoComplete="off"
                />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />
      </div>
      <div className="grid grid-cols-2 gap-4">
        <FormField
          control={control}
          name={`environmentConfigs.${index}.memoryRequest`}
          render={({ field }) => (
            <FormItem>
              <FormLabel>内存 请求 (Mi)</FormLabel>
              <FormControl>
                <Input 
                  placeholder="例如 128"
                  {...field}
                  autoComplete="off"
                />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />
        <FormField
          control={control}
          name={`environmentConfigs.${index}.memoryLimit`}
          render={({ field }) => (
            <FormItem>
              <FormLabel>内存 限制 (Mi)</FormLabel>
              <FormControl>
                <Input 
                  placeholder="例如 512"
                  {...field}
                  autoComplete="off"
                />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />
      </div>
    </div>
  )
}
