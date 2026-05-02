"use client"

import { forwardRef, useCallback, useEffect, useState } from "react"
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
import { ApplicationRuntimeSpecFormValues, applicationRuntimeSpecSchema } from "../schema"
import { TabsContent } from "@/components/ui/tabs"
import { ApplicationRuntimeSpec as ApplicationRuntimeSpecType, ApplicationEnvironment } from "@/lib/api/types"
import { updateApplicationRuntimeSpec } from "@/lib/api/applications"
import { Activity, ChevronDown, Copy, Cpu, MemoryStick, Timer, RotateCcw, TimerOff, ShieldAlert, Route, Gauge } from "lucide-react"
import { toast } from "sonner"
import { ApplicationEnvironmentSelector } from "./application-environment-selector"
import { Skeleton } from "@/components/ui/skeleton"
import { useLanguage } from "@/contexts/language-context"
import { ApplicationTabHandle } from "./application-tab-handle"
import { useApplicationEditorTab } from "./use-application-editor-tab"
import { Switch } from "@/components/ui/switch"
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible"

interface ApplicationRuntimeSpecProps {
  initialRuntimeSpec?: ApplicationRuntimeSpecType
  applicationId?: string
  applicationName?: string
  namespace?: string
  onSaved?: (
    runtimeSpec: ApplicationRuntimeSpecType
  ) => void
}

export const ApplicationRuntimeSpec = forwardRef<ApplicationTabHandle, ApplicationRuntimeSpecProps>(function ApplicationRuntimeSpec({
  initialRuntimeSpec,
  applicationId,
  applicationName,
  namespace,
  onSaved,
}: ApplicationRuntimeSpecProps, ref) {
  const initialHealthCheck = {
    enabled: false,
    path: "/",
    initialDelaySeconds: 30,
    periodSeconds: 10,
    timeoutSeconds: 3,
    failureThreshold: 3,
    ...initialRuntimeSpec?.healthCheck,
  }

  const form = useForm<ApplicationRuntimeSpecFormValues>({
    resolver: zodResolver(applicationRuntimeSpecSchema),
    defaultValues: {
      environmentConfigs: initialRuntimeSpec?.environmentConfigs ?? [],
      healthCheck: initialHealthCheck,
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

  const buildSnapshot = useCallback((values: ApplicationRuntimeSpecFormValues = form.getValues()) => JSON.stringify({
    environmentConfigs: (values.environmentConfigs ?? []).map((config) => ({
      environmentName: config.environmentName,
      replicas: config.replicas,
      cpuRequest: config.cpuRequest ?? "",
      cpuLimit: config.cpuLimit ?? "",
      memoryRequest: config.memoryRequest ?? "",
      memoryLimit: config.memoryLimit ?? "",
    })),
    healthCheck: {
      enabled: !!values.healthCheck?.enabled,
      path: values.healthCheck?.path ?? "",
      initialDelaySeconds: values.healthCheck?.initialDelaySeconds ?? 30,
      periodSeconds: values.healthCheck?.periodSeconds ?? 10,
      timeoutSeconds: values.healthCheck?.timeoutSeconds ?? 3,
      failureThreshold: values.healthCheck?.failureThreshold ?? 3,
    },
  }), [form])

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
          cpuRequest: "",
          cpuLimit: "",
          memoryRequest: "",
          memoryLimit: "",
        }
      )
    })
    const nextValues = {
      environmentConfigs: newConfigs,
      healthCheck: form.getValues("healthCheck") ?? initialHealthCheck,
    }
    replace(newConfigs)
    form.reset(nextValues)
    captureBaseline()

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

  async function submitForm(data: ApplicationRuntimeSpecFormValues) {
    if (!applicationId || !applicationName || !namespace) {
      toast.error(t("apps.runtimeSpec.noAppInfo"))
      return false
    }

    setIsSaving(true)
    try {
      await updateApplicationRuntimeSpec(namespace, applicationName, data)
      toast.success(t("apps.runtimeSpec.saveSuccess"))
      onSaved?.({
        namespace,
        applicationName,
        environmentConfigs: data.environmentConfigs,
        healthCheck: data.healthCheck,
      })
      form.reset(data)
      return true
    } catch (error) {
      console.error(error)
      toast.error(t("apps.runtimeSpec.saveError"))
      return false
    } finally {
      setIsSaving(false)
    }
  }

  const saveCurrentTab = async () => {
    let success = false
    await form.handleSubmit(async (data) => {
      success = await submitForm(data)
    })()
    return success
  }

  const { captureBaseline, handleSubmit } = useApplicationEditorTab({
    ref,
    form,
    isReady: !envsLoading,
    getSnapshot: buildSnapshot,
    onSave: saveCurrentTab,
    onSubmit: submitForm,
  })

  return (
    <>
      {envsLoading && (
        <div className="flex w-full flex-col gap-4">
          <div className="border rounded-lg overflow-hidden">
            <div className="flex items-center gap-2 px-4 py-3 border-b">
              <Skeleton className="h-4 w-4 rounded-sm" />
              <Skeleton className="h-4 w-28" />
            </div>
            <div className="flex flex-col gap-4 p-4">
              <div className="flex gap-2">
                <Skeleton className="h-9 w-20" />
                <Skeleton className="h-9 w-20" />
              </div>
              <Skeleton className="h-48 w-full" />
            </div>
          </div>
          <div className="border rounded-lg overflow-hidden">
            <div className="flex items-center gap-2 px-4 py-3 border-b">
              <Skeleton className="h-4 w-4 rounded-sm" />
              <Skeleton className="h-4 w-28" />
            </div>
            <div className="flex flex-col gap-4 p-4">
              <Skeleton className="h-10 w-full" />
              <Skeleton className="h-10 w-64" />
              <Skeleton className="h-24 w-full" />
            </div>
          </div>
        </div>
      )}
      <div className={envsLoading ? "hidden" : "w-full"}>
        <Form {...form}>
          <form onSubmit={handleSubmit} className="flex flex-col gap-4">
            <div className="border rounded-lg overflow-hidden">
              <div className="flex items-center gap-2 px-4 py-3 bg-muted/50 border-b">
                <Gauge className="h-4 w-4 text-muted-foreground" />
                <span className="text-sm font-semibold">{t("apps.runtimeSpec.title")}</span>
              </div>
              <div className="flex flex-col gap-4 p-4">
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
            </div>
            <div className="border rounded-lg overflow-hidden">
              <div className="flex items-center gap-3 px-4 py-3 bg-muted/50 border-b">
                <Activity className="h-4 w-4 text-muted-foreground" />
                <span className="text-sm font-semibold">{t("apps.runtimeSpec.healthCheck")}</span>
                <FormField
                  control={form.control}
                  name="healthCheck.enabled"
                  render={({ field }) => (
                    <FormItem>
                      <FormControl>
                        <Switch
                          checked={!!field.value}
                          onCheckedChange={field.onChange}
                          aria-label={t("apps.runtimeSpec.healthCheck")}
                        />
                      </FormControl>
                    </FormItem>
                  )}
                />
              </div>
              <div className="flex flex-col gap-4 p-4">
                <HealthCheckConfig />
              </div>
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
        inputMode="numeric"
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

function NumberInput({ value, onChange, min = 0 }: { value: number | undefined, onChange: (v: number | undefined) => void, min?: number }) {
  return (
    <Input
      type="number"
      min={min}
      step={1}
      inputMode="numeric"
      value={value ?? ""}
      onChange={(event) => {
        const raw = event.target.value.replace(/[^0-9]/g, "")
        onChange(raw === "" ? undefined : parseInt(raw, 10))
      }}
      autoComplete="off"
      className="w-24"
    />
  )
}

function HealthCheckConfig() {
  const { control } = useFormContext<ApplicationRuntimeSpecFormValues>()
  const { t } = useLanguage()

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-col gap-3">
        <div className="flex flex-wrap items-end gap-2">
          <FormField
            control={control}
            name="healthCheck.path"
            render={({ field }) => (
              <FormItem>
                <FormLabel className="flex items-center gap-1"><Route className="h-3.5 w-3.5" />{t("apps.runtimeSpec.healthPath")}</FormLabel>
                <FormControl>
                  <Input
                    placeholder="/"
                    {...field}
                    value={field.value ?? ""}
                    autoComplete="off"
                    className="w-64"
                  />
                </FormControl>
                <FormMessage />
              </FormItem>
            )}
          />
          <Collapsible className="contents">
            <CollapsibleTrigger asChild>
              <Button type="button" variant="ghost" size="sm" className="w-fit px-0">
                <ChevronDown className="h-4 w-4" />
                {t("apps.runtimeSpec.advanced")}
              </Button>
            </CollapsibleTrigger>
            <CollapsibleContent className="w-full">
              <div className="flex flex-wrap gap-x-6 gap-y-4 pt-3">
                <FormField
                  control={control}
                  name="healthCheck.initialDelaySeconds"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel className="flex items-center gap-1"><Timer className="h-3.5 w-3.5" />{t("apps.runtimeSpec.initialDelay")}</FormLabel>
                      <FormControl>
                        <NumberInput value={field.value} onChange={field.onChange} min={0} />
                      </FormControl>
                      <FormMessage />
                    </FormItem>
                  )}
                />
                <FormField
                  control={control}
                  name="healthCheck.periodSeconds"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel className="flex items-center gap-1"><RotateCcw className="h-3.5 w-3.5" />{t("apps.runtimeSpec.period")}</FormLabel>
                      <FormControl>
                        <NumberInput value={field.value} onChange={field.onChange} min={1} />
                      </FormControl>
                      <FormMessage />
                    </FormItem>
                  )}
                />
                <FormField
                  control={control}
                  name="healthCheck.timeoutSeconds"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel className="flex items-center gap-1"><TimerOff className="h-3.5 w-3.5" />{t("apps.runtimeSpec.timeout")}</FormLabel>
                      <FormControl>
                        <NumberInput value={field.value} onChange={field.onChange} min={1} />
                      </FormControl>
                      <FormMessage />
                    </FormItem>
                  )}
                />
                <FormField
                  control={control}
                  name="healthCheck.failureThreshold"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel className="flex items-center gap-1"><ShieldAlert className="h-3.5 w-3.5" />{t("apps.runtimeSpec.failureThreshold")}</FormLabel>
                      <FormControl>
                        <NumberInput value={field.value} onChange={field.onChange} min={1} />
                      </FormControl>
                      <FormMessage />
                    </FormItem>
                  )}
                />
              </div>
            </CollapsibleContent>
          </Collapsible>
        </div>
      </div>
    </div>
  )
}

function SingleEnvironmentConfig({ index }: SingleEnvironmentConfigProps) {
  const { control } = useFormContext<ApplicationRuntimeSpecFormValues>()
  const { t } = useLanguage()

  return (
    <div className="flex flex-col gap-4">
      <FormField
        control={control}
        name={`environmentConfigs.${index}.replicas`}
        render={({ field }) => (
          <FormItem>
            <FormLabel className="flex items-center gap-1"><Copy className="h-3.5 w-3.5" />{t("apps.runtimeSpec.replicas")}</FormLabel>
            <FormControl>
              <ReplicasInput value={field.value} onChange={field.onChange} />
            </FormControl>
            <FormMessage />
          </FormItem>
        )}
      />
      <div className="inline-grid self-start grid-cols-2 gap-x-8 gap-y-4">
        <FormField
          control={control}
          name={`environmentConfigs.${index}.cpuRequest`}
          render={({ field }) => (
            <FormItem>
              <FormLabel className="flex items-center gap-1"><Cpu className="h-3.5 w-3.5" />{t("apps.runtimeSpec.cpuRequest")}</FormLabel>
              <FormControl>
                <div className="relative w-24">
                  <Input {...field} autoComplete="off" className="pr-10" />
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
              <FormLabel className="flex items-center gap-1"><Cpu className="h-3.5 w-3.5" />{t("apps.runtimeSpec.cpuLimit")}</FormLabel>
              <FormControl>
                <div className="relative w-24">
                  <Input {...field} autoComplete="off" className="pr-10" />
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
              <FormLabel className="flex items-center gap-1"><MemoryStick className="h-3.5 w-3.5" />{t("apps.runtimeSpec.memRequest")}</FormLabel>
              <FormControl>
                <div className="relative w-24">
                  <Input {...field} autoComplete="off" className="pr-8" />
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
              <FormLabel className="flex items-center gap-1"><MemoryStick className="h-3.5 w-3.5" />{t("apps.runtimeSpec.memLimit")}</FormLabel>
              <FormControl>
                <div className="relative w-24">
                  <Input {...field} autoComplete="off" className="pr-8" />
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
