"use client"

import { forwardRef, useEffect, useImperativeHandle, useRef, useState } from "react"
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
import { ApplicationPerformanceConfigEnvironmentConfig, ApplicationEnvironment } from "@/lib/api/types"
import { updateApplicationPerformanceEnvConfigs } from "@/lib/api/applications"
import { Cpu, MemoryStick, Copy } from "lucide-react"
import { toast } from "sonner"
import { ApplicationEnvironmentSelector } from "./application-environment-selector"
import { Skeleton } from "@/components/ui/skeleton"
import { useLanguage } from "@/contexts/language-context"
import { ApplicationTabHandle } from "./application-tab-handle"

interface ApplicationPerformanceInfoProps {
  initialEnvConfigs?: ApplicationPerformanceConfigEnvironmentConfig[]
  applicationId?: string
  applicationName?: string
  namespace?: string
  onSaved?: (
    envConfigs: ApplicationPerformanceConfigEnvironmentConfig[]
  ) => void
}

export const ApplicationPerformanceInfo = forwardRef<ApplicationTabHandle, ApplicationPerformanceInfoProps>(function ApplicationPerformanceInfo({
  initialEnvConfigs = [],
  applicationId,
  applicationName,
  namespace,
  onSaved,
}: ApplicationPerformanceInfoProps, ref) {
  const form = useForm<ApplicationPerformanceEnvFormValues>({
    resolver: zodResolver(applicationPerformanceEnvSchema),
    defaultValues: {
      environmentConfigs: initialEnvConfigs,
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
  const [envsLoading, setEnvsLoading] = useState(!!(namespace && applicationName))
  const { t } = useLanguage()
  const baselineRef = useRef("")

  const buildSnapshot = (values: ApplicationPerformanceEnvFormValues = form.getValues()) => JSON.stringify({
    environmentConfigs: (values.environmentConfigs ?? []).map((config) => ({
      environmentName: config.environmentName,
      replicas: config.replicas,
      cpuRequest: config.cpuRequest ?? "",
      cpuLimit: config.cpuLimit ?? "",
      memoryRequest: config.memoryRequest ?? "",
      memoryLimit: config.memoryLimit ?? "",
    })),
  })

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
    const nextValues = {
      environmentConfigs: newConfigs,
    }
    replace(newConfigs)
    form.reset(nextValues)
    baselineRef.current = buildSnapshot(nextValues)

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

  const submitForm = async (data: ApplicationPerformanceEnvFormValues) => {
    if (!applicationId || !applicationName || !namespace) {
      toast.error(t("apps.perf.noAppInfo"))
      return false
    }

    setIsSaving(true)
    try {
      await updateApplicationPerformanceEnvConfigs(namespace, applicationName, data.environmentConfigs)
      toast.success(t("apps.perf.saveSuccess"))
      onSaved?.(data.environmentConfigs)
      form.reset(data)
      baselineRef.current = buildSnapshot(data)
      return true
    } catch (error) {
      console.error(error)
      toast.error(t("apps.perf.saveError"))
      return false
    } finally {
      setIsSaving(false)
    }
  }

  useImperativeHandle(ref, () => ({
    hasUnsavedChanges() {
      if (envsLoading) {
        return false
      }
      return buildSnapshot() !== baselineRef.current
    },
    async save() {
      if (envsLoading) {
        return true
      }

      let success = false
      await form.handleSubmit(async (data) => {
        success = await submitForm(data)
      })()
      return success
    },
  }))

  return (
    <>
      {envsLoading && (
        <div className="flex flex-col gap-3">
          <Skeleton className="h-9 w-64" />
          <Skeleton className="h-48 w-full" />
        </div>
      )}
      <div className={envsLoading ? "hidden" : ""}>
        <Form {...form}>
          <form onSubmit={form.handleSubmit(async (data) => { await submitForm(data) })} className="flex flex-col gap-6">
            <div className="w-full">
              <ApplicationEnvironmentSelector
                namespace={namespace}
                applicationName={applicationName}
                value={activeTab}
                onValueChange={setActiveTab}
                onEnvironmentsLoaded={handleEnvironmentsLoaded}
                onLoadingChange={setEnvsLoading}
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
            <div className="flex">
              <Button type="submit" disabled={isSaving}>
                {isSaving ? t("common.saving") : t("common.save")}
              </Button>
            </div>
          </form>
        </Form>
      </div>
    </>
  )
})

interface SingleEnvironmentConfigProps {
  index: number
}

function ReplicasInput({ value, onChange }: { value: number | undefined, onChange: (v: number | undefined) => void }) {
  const text = value != null ? String(value) : ''

  const update = (raw: string) => {
    onChange(raw === '' ? undefined : parseInt(raw, 10))
  }

  return (
    <div className="flex items-center gap-2">
      <Button
        type="button"
        variant="outline"
        size="icon"
        className="h-8 w-8 shrink-0"
        onClick={() => {
          const next = Math.max(0, (parseInt(text, 10) || 0) - 1)
          update(String(next))
        }}
      >
        −
      </Button>
      <Input
        value={text}
        onChange={e => update(e.target.value.replace(/[^0-9]/g, ''))}
        autoComplete="off"
        className="w-16 text-center"
      />
      <Button
        type="button"
        variant="outline"
        size="icon"
        className="h-8 w-8 shrink-0"
        onClick={() => {
          const next = (parseInt(text, 10) || 0) + 1
          update(String(next))
        }}
      >
        +
      </Button>
    </div>
  )
}

function SingleEnvironmentConfig({ index }: SingleEnvironmentConfigProps) {
  const { control } = useFormContext<ApplicationPerformanceEnvFormValues>()
  const { t } = useLanguage()

  return (
    <div className="flex flex-col gap-4">
      <FormField
        control={control}
        name={`environmentConfigs.${index}.replicas`}
        render={({ field }) => (
          <FormItem>
            <FormLabel className="flex items-center gap-1"><Copy className="h-3.5 w-3.5" />{t("apps.perf.replicas")}</FormLabel>
            <FormControl>
              <ReplicasInput value={field.value} onChange={field.onChange} />
            </FormControl>
            <FormMessage />
          </FormItem>
        )}
      />
      <div className="grid grid-cols-2 gap-x-4 gap-y-4 w-fit">
        <FormField
          control={control}
          name={`environmentConfigs.${index}.cpuRequest`}
          render={({ field }) => (
            <FormItem>
              <FormLabel className="flex items-center gap-1"><Cpu className="h-3.5 w-3.5" />{t("apps.perf.cpuRequest")}</FormLabel>
              <FormControl>
                <div className="relative w-24">
                  <Input placeholder="0.1" {...field} autoComplete="off" className="pr-10" />
                  <span className="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 text-xs text-muted-foreground">core</span>
                </div>
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
              <FormLabel className="flex items-center gap-1"><Cpu className="h-3.5 w-3.5" />{t("apps.perf.cpuLimit")}</FormLabel>
              <FormControl>
                <div className="relative w-24">
                  <Input placeholder="1" {...field} autoComplete="off" className="pr-10" />
                  <span className="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 text-xs text-muted-foreground">core</span>
                </div>
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />
        <FormField
          control={control}
          name={`environmentConfigs.${index}.memoryRequest`}
          render={({ field }) => (
            <FormItem>
              <FormLabel className="flex items-center gap-1"><MemoryStick className="h-3.5 w-3.5" />{t("apps.perf.memRequest")}</FormLabel>
              <FormControl>
                <div className="relative w-24">
                  <Input placeholder="128" {...field} autoComplete="off" className="pr-8" />
                  <span className="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 text-xs text-muted-foreground">Mi</span>
                </div>
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
              <FormLabel className="flex items-center gap-1"><MemoryStick className="h-3.5 w-3.5" />{t("apps.perf.memLimit")}</FormLabel>
              <FormControl>
                <div className="relative w-24">
                  <Input placeholder="512" {...field} autoComplete="off" className="pr-8" />
                  <span className="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 text-xs text-muted-foreground">Mi</span>
                </div>
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />
      </div>
    </div>
  )
}
