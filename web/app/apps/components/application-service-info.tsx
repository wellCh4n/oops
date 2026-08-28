"use client"

import { forwardRef, useCallback, useEffect, useMemo, useRef, useState } from "react"
import { useFieldArray, useForm, useWatch } from "react-hook-form"
import { zodResolver } from "@hookform/resolvers/zod"
import { Label } from "@/components/ui/label"
import { Input } from "@/components/ui/input"
import { Button } from "@/components/ui/button"
import { TagsInput } from "@/components/ui/tags-input"
import { Badge } from "@/components/ui/badge"
import { Form, FormControl, FormField, FormItem, FormMessage } from "@/components/ui/form"
import { toast } from "sonner"
import { TabsContent } from "@/components/ui/tabs"
import { Copyable } from "@/components/ui/copyable"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"
import { ExternalLink, Lock, LockOpen, Pencil, Plug, Globe, Info, KeyRound, Plus, Trash2, Network } from "lucide-react"
import { ApplicationEnvironment, ApplicationServiceConfig, ApplicationServiceEnvironmentConfig } from "@/lib/api/types"
import { updateApplicationService, checkApplicationServiceHost } from "@/lib/api/applications"
import { Domain, fetchDomains } from "@/lib/api/domains"
import { ApplicationEnvironmentSelector } from "./application-environment-selector"
import Link from "next/link"
import { useLanguage } from "@/contexts/language-context"
import { ApplicationTabHandle } from "./application-tab-handle"
import { useApplicationEditorTab } from "./use-application-editor-tab"
import { ApplicationServiceFormValues, applicationServiceSchema } from "../schema"
import { ApplicationEditorTabSkeleton } from "./application-editor-skeleton"
import { HostEditorDialog, HostEditorValue } from "./host-editor-dialog"

interface Props {
  initialServiceConfig?: ApplicationServiceConfig
  applicationName?: string
  namespace?: string
  onSaved?: (serviceConfig: ApplicationServiceConfig) => void
}

type HostFormValue = ApplicationServiceFormValues["environmentConfigs"][number]["hosts"][number]

interface HostEditorTarget {
  environment: string
  /** Undefined while adding a new host. */
  hostIndex?: number
}

function buildServiceFormValues(initialServiceConfig?: ApplicationServiceConfig): ApplicationServiceFormValues {
  const grouped = new Map<string, ApplicationServiceFormValues["environmentConfigs"][number]["hosts"]>()

  initialServiceConfig?.environmentConfigs?.forEach((config) => {
    const hosts = grouped.get(config.environment) ?? []
    hosts.push({
      host: config.host?.replace(/^https?:\/\//i, "") ?? "",
      https: config.https ?? true,
      basicAuthEnabled: config.basicAuthEnabled ?? false,
      basicAuthUsername: config.basicAuthUsername ?? "",
      basicAuthPassword: "",
      basicAuthPasswordSet: config.basicAuthPasswordSet ?? false,
    })
    grouped.set(config.environment, hosts)
  })

  return {
    port: initialServiceConfig?.port != null ? String(initialServiceConfig.port) : "",
    internalPorts: (initialServiceConfig?.internalPorts ?? []).map((port) => String(port)),
    environmentConfigs: Array.from(grouped.entries()).map(([environment, hosts]) => ({
      environment,
      hosts,
    })),
  }
}

export const ApplicationServiceInfo = forwardRef<ApplicationTabHandle, Props>(function ApplicationServiceInfo({
  initialServiceConfig,
  applicationName,
  namespace,
  onSaved,
}: Props, ref) {
  const [activeTab, setActiveTab] = useState<string | undefined>(undefined)
  const [saving, setSaving] = useState(false)
  const [envsLoading, setEnvsLoading] = useState(!!(namespace && applicationName))
  const [domains, setDomains] = useState<Domain[]>([])
  const [hostEditorTarget, setHostEditorTarget] = useState<HostEditorTarget | null>(null)
  const { t } = useLanguage()

  const form = useForm<ApplicationServiceFormValues>({
    resolver: zodResolver(applicationServiceSchema),
    defaultValues: buildServiceFormValues(initialServiceConfig),
  })

  const { fields: envFields } = useFieldArray({
    control: form.control,
    name: "environmentConfigs",
  })

  const internalPorts = useWatch({ control: form.control, name: "internalPorts" }) ?? []

  // In-progress (not yet committed) internal-port text. Tracked so it participates in the
  // unsaved-changes snapshot and is flushed into the payload on save (avoids silent loss).
  const [pendingInternalPort, setPendingInternalPort] = useState("")
  const pendingInternalPortRef = useRef("")
  const handleInternalPortInputChange = useCallback((value: string) => {
    pendingInternalPortRef.current = value
    setPendingInternalPort(value)
  }, [])

  const environmentConfigsWatch = useWatch({ control: form.control, name: "environmentConfigs" })
  const environmentConfigs = useMemo(() => environmentConfigsWatch ?? [], [environmentConfigsWatch])

  const environmentIndexByName = useMemo(() => {
    return new Map(environmentConfigs.map((group, index) => [group.environment, index]))
  }, [environmentConfigs])

  const editingHost = useMemo<HostFormValue | undefined>(() => {
    if (!hostEditorTarget || hostEditorTarget.hostIndex == null) {
      return undefined
    }
    const environmentIndex = environmentIndexByName.get(hostEditorTarget.environment)
    if (environmentIndex == null) {
      return undefined
    }
    return form.getValues(`environmentConfigs.${environmentIndex}.hosts.${hostEditorTarget.hostIndex}`)
  }, [environmentIndexByName, form, hostEditorTarget])

  const buildSnapshot = useCallback((values: ApplicationServiceFormValues = form.getValues()) => JSON.stringify({
    port: values.port?.trim() ?? "",
    internalPorts: (values.internalPorts ?? []).map((port) => port.trim()),
    pendingInternalPort: pendingInternalPortRef.current.trim(),
    environmentConfigs: (values.environmentConfigs ?? []).map((group) => ({
      environment: group.environment,
      hosts: group.hosts.map((host) => ({
        host: host.host,
        https: host.https,
        basicAuthEnabled: host.basicAuthEnabled,
        basicAuthUsername: host.basicAuthUsername,
        basicAuthPassword: host.basicAuthPassword,
      })),
    })),
  }), [form])

  const setHosts = useCallback((environment: string, nextHosts: ApplicationServiceFormValues["environmentConfigs"][number]["hosts"]) => {
    const environmentIndex = environmentIndexByName.get(environment)
    if (environmentIndex == null) {
      return
    }

    form.setValue(`environmentConfigs.${environmentIndex}.hosts`, nextHosts, {
      shouldDirty: true,
      shouldTouch: true,
    })
  }, [environmentIndexByName, form])

  /** Resolves to an error message when the host is already taken, in this form or by another application. */
  const checkHost = useCallback(async (host: string): Promise<string | null> => {
    if (!namespace || !applicationName || !hostEditorTarget) return null

    const formatMessage = (name: string, ns: string, env: string) =>
      t("apps.service.hostDuplicated")
        .replace("{name}", name)
        .replace("{namespace}", ns)
        .replace("{environment}", env)

    for (const group of environmentConfigs) {
      for (let index = 0; index < group.hosts.length; index += 1) {
        const isSelf = group.environment === hostEditorTarget.environment && index === hostEditorTarget.hostIndex
        if (!isSelf && group.hosts[index].host.trim() === host) {
          return formatMessage(applicationName, namespace, group.environment)
        }
      }
    }

    try {
      const result = await checkApplicationServiceHost(namespace, applicationName, host)
      const duplicatedHost = result.data
      if (result.success && duplicatedHost) {
        return formatMessage(duplicatedHost.applicationName, duplicatedHost.namespace, duplicatedHost.environment)
      }
    } catch {
      // transient errors are re-checked server-side on save
    }
    return null
  }, [applicationName, environmentConfigs, hostEditorTarget, namespace, t])

  const confirmHostEditor = useCallback((value: HostEditorValue) => {
    if (!hostEditorTarget) return
    const environmentIndex = environmentIndexByName.get(hostEditorTarget.environment)
    if (environmentIndex == null) return

    const hosts = form.getValues(`environmentConfigs.${environmentIndex}.hosts`)
    const nextHosts = hostEditorTarget.hostIndex == null
      ? [...hosts, value]
      : hosts.map((host, index) => (index === hostEditorTarget.hostIndex ? value : host))
    setHosts(hostEditorTarget.environment, nextHosts)
  }, [environmentIndexByName, form, hostEditorTarget, setHosts])

  const removeHost = useCallback((environment: string, hostIndex: number) => {
    const environmentIndex = environmentIndexByName.get(environment)
    if (environmentIndex == null) {
      return
    }

    const hosts = form.getValues(`environmentConfigs.${environmentIndex}.hosts`)
    setHosts(environment, hosts.filter((_, index) => index !== hostIndex))
  }, [environmentIndexByName, form, setHosts])


  const validateInternalPort = useCallback((value: string) => {
    // Require a plain decimal integer in range — reject "1e4", "9090.0", "+80", etc.
    if (!/^\d+$/.test(value) || Number(value) <= 0 || Number(value) > 65535) {
      return t("apps.service.internalPortError")
    }
    return null
  }, [t])

  const setInternalPorts = useCallback((next: string[]) => {
    form.setValue("internalPorts", next, { shouldDirty: true, shouldTouch: true })
  }, [form])

  useEffect(() => {
    fetchDomains().then(setDomains).catch(() => setDomains([]))
  }, [])

  useEffect(() => {
    const built = buildServiceFormValues(initialServiceConfig)
    const currentConfigs = form.getValues("environmentConfigs") ?? []

    const mergedConfigs = currentConfigs.map((current) => {
      const builtGroup = built.environmentConfigs.find(
        (g) => g.environment === current.environment
      )
      return {
        environment: current.environment,
        hosts: builtGroup?.hosts ?? [],
      }
    })

    built.environmentConfigs.forEach((builtGroup) => {
      if (!mergedConfigs.some((c) => c.environment === builtGroup.environment)) {
        mergedConfigs.push(builtGroup)
      }
    })

    form.reset({
      port: built.port,
      internalPorts: built.internalPorts,
      environmentConfigs: mergedConfigs,
    })
  }, [form, initialServiceConfig])

  useEffect(() => {
    if (environmentConfigs.length > 0 && !activeTab) {
      setActiveTab(environmentConfigs[0].environment)
    }
  }, [activeTab, environmentConfigs])

  const handleEnvironmentsLoaded = useCallback((environments: ApplicationEnvironment[]) => {
    const currentConfigs = form.getValues("environmentConfigs")
    const nextConfigs = environments.map((environment) => {
      const existing = currentConfigs.find((config) => config.environment === environment.environment)
      return existing ?? {
        environment: environment.environment,
        hosts: [],
      }
    })

    form.reset({
      port: form.getValues("port"),
      internalPorts: form.getValues("internalPorts"),
      environmentConfigs: nextConfigs,
    })

    if (nextConfigs.length > 0 && !activeTab) {
      setActiveTab(nextConfigs[0].environment)
    }
  }, [activeTab, form])

  const submitForm = useCallback(async (values: ApplicationServiceFormValues) => {
    if (!namespace || !applicationName) return false

    const trimmedPort = values.port.trim()
    let portValue: number | undefined
    if (trimmedPort) {
      const portNumber = Number(trimmedPort)
      if (!Number.isInteger(portNumber) || portNumber <= 0 || portNumber > 65535) {
        toast.error(t("apps.service.portError"))
        return false
      }
      portValue = portNumber
    }

    const internalPortsPayload: number[] = []
    // Include any text typed but not yet committed to a tag, so a pending value isn't silently dropped.
    const internalPortEntries = [...values.internalPorts, pendingInternalPortRef.current]
    for (const entry of internalPortEntries) {
      const trimmed = entry.trim()
      if (!trimmed) {
        continue
      }
      if (!/^\d+$/.test(trimmed) || Number(trimmed) <= 0 || Number(trimmed) > 65535) {
        toast.error(t("apps.service.internalPortError"))
        return false
      }
      const portNumber = Number(trimmed)
      if (!internalPortsPayload.includes(portNumber)) {
        internalPortsPayload.push(portNumber)
      }
    }


    const environmentConfigsPayload: ApplicationServiceEnvironmentConfig[] = []
    for (const group of values.environmentConfigs) {
      for (const host of group.hosts) {
        if (!host.host.trim()) {
          continue
        }

        if (host.basicAuthEnabled) {
          if (!host.basicAuthUsername.trim()) {
            toast.error(t("apps.service.basicAuth.usernameRequired"))
            return false
          }
          if (!host.basicAuthPassword && !host.basicAuthPasswordSet) {
            toast.error(t("apps.service.basicAuth.passwordRequired"))
            return false
          }
        }

        environmentConfigsPayload.push({
          environment: group.environment,
          host: host.host.trim(),
          https: host.https,
          basicAuthEnabled: host.basicAuthEnabled,
          basicAuthUsername: host.basicAuthEnabled ? host.basicAuthUsername.trim() : undefined,
          // Blank means "keep the stored password" — the backend never hands the hash back.
          basicAuthPassword: host.basicAuthEnabled ? host.basicAuthPassword || undefined : undefined,
        })
      }
    }

    setSaving(true)
    try {
      const result = await updateApplicationService(namespace, applicationName, {
        port: portValue,
        internalPorts: internalPortsPayload,
        environmentConfigs: environmentConfigsPayload,
      })
      if (!result.success) {
        toast.error(result.message || t("apps.service.saveError"))
        return false
      }

      toast.success(t("apps.service.saveSuccess"))
      // Mirror what the server now returns: the plaintext password is gone, only the "a password
      // exists" marker survives.
      onSaved?.({
        port: portValue,
        internalPorts: internalPortsPayload,
        environmentConfigs: environmentConfigsPayload.map((config) => ({
          ...config,
          basicAuthPassword: undefined,
          basicAuthPasswordSet: config.basicAuthEnabled,
        })),
      })
      pendingInternalPortRef.current = ""
      setPendingInternalPort("")
      form.reset({
        ...values,
        internalPorts: internalPortsPayload.map(String),
        environmentConfigs: values.environmentConfigs.map((group) => ({
          ...group,
          hosts: group.hosts.map((host) => ({
            ...host,
            basicAuthPassword: "",
            basicAuthPasswordSet: host.basicAuthEnabled,
          })),
        })),
      })
      return true
    } catch {
      toast.error(t("apps.service.saveError"))
      return false
    } finally {
      setSaving(false)
    }
  }, [applicationName, form, namespace, onSaved, t])

  const saveCurrentTab = useCallback(async () => {
    let success = false
    await form.handleSubmit(async (values) => {
      success = await submitForm(values)
    })()
    return success
  }, [form, submitForm])

  const { handleSubmit } = useApplicationEditorTab({
    ref,
    form,
    isReady: !envsLoading,
    getSnapshot: buildSnapshot,
    onSave: saveCurrentTab,
    onSubmit: submitForm,
    initializeBaselineWhenReady: true,
  })

  return (
    <>
      {envsLoading && <ApplicationEditorTabSkeleton />}
      <div className={envsLoading ? "hidden" : "w-full"}>
        <Form {...form}>
          <form onSubmit={handleSubmit} className="flex w-full flex-col gap-4">
        <div className="border rounded-lg overflow-hidden">
          <div className="flex items-center gap-2 px-4 py-3 bg-muted/50 border-b">
            <Network className="size-4 text-muted-foreground" />
            <span className="text-sm font-semibold">{t("apps.service.accessEntry")}</span>
            <Tooltip>
              <TooltipTrigger render={<button type="button" className="text-muted-foreground hover:text-foreground inline-flex items-center cursor-pointer" aria-label={t("apps.service.accessEntryRepublishNotice")} />}>
                <Info className="size-3.5" />
              </TooltipTrigger>
              <TooltipContent className="max-w-xs text-xs">
                {t("apps.service.accessEntryRepublishNotice")}
              </TooltipContent>
            </Tooltip>
          </div>
          <div className="flex flex-col gap-4 p-4">
            <div className="grid grid-cols-1 items-start gap-4 sm:grid-cols-3">
            <div className="grid gap-2 sm:col-span-1">
            <Label htmlFor="service-port" className="flex items-center gap-1">
              <Plug className="size-3.5" />
              {t("apps.service.port")}
            </Label>
            <FormField
              control={form.control}
              name="port"
              render={({ field }) => (
                <FormItem>
                  <FormControl>
                    <Input
                      {...field}
                      autoComplete="off"
                      id="service-port"
                      type="number"
                      inputMode="numeric"
                      placeholder={t("apps.service.portPlaceholder")}
                      min={1}
                      max={65535}
                    />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />
          </div>

          <div className="grid gap-2 sm:col-span-2">
            <Label className="flex items-center gap-1">
              <Plug className="size-3.5" />
              {t("apps.service.internalPorts")}
              <Tooltip>
                <TooltipTrigger render={<button type="button" className="text-muted-foreground hover:text-foreground inline-flex items-center cursor-pointer" aria-label={t("apps.service.internalPortsHint")} />}>
                  <Info className="size-3.5" />
                </TooltipTrigger>
                <TooltipContent className="max-w-xs text-xs">
                  {t("apps.service.internalPortsHint")}
                </TooltipContent>
              </Tooltip>
            </Label>
            <TagsInput
              values={internalPorts}
              onValuesChange={setInternalPorts}
              inputValue={pendingInternalPort}
              onInputValueChange={handleInternalPortInputChange}
              validate={validateInternalPort}
              transform={(value) => String(Number(value))}
              onError={(message) => toast.error(message)}
              inputMode="numeric"
              placeholder={t("apps.service.internalPortPlaceholder")}
              removeAriaLabel={t("apps.service.removeInternalPort")}
            />
          </div>
          </div>

          <Label className="flex items-center gap-1">
            <Globe className="size-3.5" />
            {t("apps.service.hosts")}
          </Label>

          <div className="flex flex-col gap-2">
            <ApplicationEnvironmentSelector
              namespace={namespace}
              applicationName={applicationName}
              value={activeTab}
              onValueChange={setActiveTab}
              onEnvironmentsLoaded={handleEnvironmentsLoaded}
              onLoadingChange={setEnvsLoading}
              className="w-full"
            >
                {envFields.map((field, environmentIndex) => {
                  const group = environmentConfigs[environmentIndex]
                  if (!group) {
                    return null
                  }
                  const environmentDomains = domains.filter(
                    (domain) => domain.environment === group.environment
                  )

                  return (
                    <TabsContent key={field.id} value={group.environment}>
                      <div className="grid gap-4">
                        {environmentDomains.length === 0 ? (
                          <div className="text-sm text-muted-foreground px-3 py-2 border rounded-md border-dashed">
                            {t("apps.service.noDomainPrefix")}
                            <Link href="/networks/domains" className="inline-flex items-center gap-0.5 text-primary mx-1">
                              <span className="hover:underline">{t("apps.service.noDomainLink")}</span>
                              <ExternalLink className="size-3" />
                            </Link>
                            {t("apps.service.noDomainSuffix")}
                          </div>
                        ) : (
                          group.hosts.length === 0 ? (
                            <div className="text-sm text-muted-foreground px-3 py-2 border rounded-md border-dashed text-center">
                              {t("apps.service.noHosts")}
                            </div>
                          ) : (
                            <div className="divide-y rounded-md border">
                              {group.hosts.map((hostConfig, hostIndex) => {
                                const url = `${hostConfig.https ? "https" : "http"}://${hostConfig.host}`
                                return (
                                  <div key={`${group.environment}-${hostIndex}`} className="flex items-center gap-2 px-3 py-2">
                                    {hostConfig.https
                                      ? <Lock className="size-4 shrink-0 text-emerald-600 dark:text-emerald-500" />
                                      : <LockOpen className="size-4 shrink-0 text-muted-foreground" />}
                                    <div className="flex min-w-0 flex-1 items-center gap-2">
                                      <Copyable value={url} maxLength={Infinity} />
                                      <a
                                        href={url}
                                        target="_blank"
                                        rel="noopener noreferrer"
                                        className="text-muted-foreground hover:text-foreground transition-colors"
                                      >
                                        <ExternalLink className="size-4" />
                                      </a>
                                    </div>
                                    {hostConfig.basicAuthEnabled && (
                                      <Tooltip>
                                        <TooltipTrigger render={<Badge variant="secondary" className="gap-1 cursor-default" />}>
                                          <KeyRound className="size-3" />
                                          {t("apps.service.basicAuth.title")}
                                        </TooltipTrigger>
                                        <TooltipContent className="text-xs">
                                          {t("apps.service.basicAuth.enabledTooltip").replace("{username}", hostConfig.basicAuthUsername)}
                                        </TooltipContent>
                                      </Tooltip>
                                    )}
                                    <Button
                                      type="button"
                                      variant="ghost"
                                      size="icon"
                                      aria-label={t("apps.service.editHost")}
                                      onClick={() => setHostEditorTarget({ environment: group.environment, hostIndex })}
                                    >
                                      <Pencil className="size-4" />
                                    </Button>
                                    <Button
                                      type="button"
                                      variant="ghost"
                                      size="icon"
                                      aria-label={t("common.delete")}
                                      onClick={() => removeHost(group.environment, hostIndex)}
                                    >
                                      <Trash2 className="size-4 text-destructive" />
                                    </Button>
                                  </div>
                                )
                              })}
                            </div>
                          )
                        )}

                        {environmentDomains.length > 0 && (
                          <Button
                            type="button"
                            variant="outline"
                            className="w-full"
                            onClick={() => setHostEditorTarget({ environment: group.environment })}
                          >
                            <Plus className="size-4 mr-1" />
                            {t("apps.service.addHost")}
                          </Button>
                        )}
                      </div>
                    </TabsContent>
                  )
                })}
            </ApplicationEnvironmentSelector>
          </div>
        </div>
      </div>

      <div>
        <Button type="submit" disabled={saving || !namespace || !applicationName}>
          {saving ? t("common.saving") : t("common.save")}
        </Button>
      </div>

      {hostEditorTarget && (
        <HostEditorDialog
          key={`${hostEditorTarget.environment}-${hostEditorTarget.hostIndex ?? "new"}`}
          value={editingHost}
          domains={domains.filter((domain) => domain.environment === hostEditorTarget.environment)}
          onOpenChange={(open) => { if (!open) setHostEditorTarget(null) }}
          checkHost={checkHost}
          onConfirm={confirmHostEditor}
        />
      )}
          </form>
        </Form>
      </div>
    </>
  )
})
