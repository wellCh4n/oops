"use client"

import { forwardRef, useCallback, useEffect, useMemo, useState } from "react"
import { useFieldArray, useForm, useWatch } from "react-hook-form"
import { zodResolver } from "@hookform/resolvers/zod"
import { Label } from "@/components/ui/label"
import { Input } from "@/components/ui/input"
import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle } from "@/components/ui/alert-dialog"
import { Form, FormControl, FormField, FormItem, FormMessage } from "@/components/ui/form"
import { toast } from "sonner"
import { TabsContent } from "@/components/ui/tabs"
import { Copyable } from "@/components/ui/copyable"
import { Check, ExternalLink, Pencil, Plug, Globe, Plus, Trash2, X, Network } from "lucide-react"
import { ApplicationEnvironment, ApplicationServiceConfig, ApplicationServiceEnvironmentConfig } from "@/lib/api/types"
import { updateApplicationService, checkApplicationServiceHost } from "@/lib/api/applications"
import { Domain, fetchDomains } from "@/lib/api/domains"
import { ApplicationEnvironmentSelector } from "./application-environment-selector"
import { Skeleton } from "@/components/ui/skeleton"
import Link from "next/link"
import { useLanguage } from "@/contexts/language-context"
import { ApplicationTabHandle } from "./application-tab-handle"
import { useApplicationEditorTab } from "./use-application-editor-tab"
import { ApplicationServiceFormValues, applicationServiceSchema } from "../schema"

interface Props {
  initialServiceConfig?: ApplicationServiceConfig
  applicationName?: string
  namespace?: string
  onSaved?: (serviceConfig: ApplicationServiceConfig) => void
}

interface HostErrorContext {
  environmentName: string
  hostIndex: number
}

function splitHost(fullHost: string, domains: Domain[]): { prefix: string; suffix: string } {
  const lower = fullHost.trim().toLowerCase()
  if (!lower) return { prefix: "", suffix: "" }

  const hosts = domains.map((domain) => domain.host).sort((a, b) => b.length - a.length)
  const matchHost = hosts.find((host) => lower === host || lower.endsWith("." + host))
  if (!matchHost) {
    return { prefix: lower, suffix: "" }
  }
  if (lower === matchHost) {
    return { prefix: "", suffix: matchHost }
  }

  return { prefix: lower.slice(0, -(matchHost.length + 1)), suffix: matchHost }
}

function combineHost(prefix: string, suffix: string) {
  const normalizedPrefix = prefix.trim().toLowerCase()
  if (!suffix) {
    return normalizedPrefix
  }

  return normalizedPrefix ? `${normalizedPrefix}.${suffix}` : suffix
}

function createEmptyHost() {
  return {
    host: "",
    https: true,
    editing: true,
    prefix: "",
    suffix: "",
  }
}

function normalizeHostForSnapshot(host: ApplicationServiceFormValues["environmentConfigs"][number]["hosts"][number]) {
  if (host.editing) {
    return {
      host: host.host,
      https: host.https,
      editing: true,
      prefix: host.prefix,
      suffix: host.suffix,
    }
  }

  return {
    host: host.host,
    https: host.https,
    editing: false,
    prefix: "",
    suffix: "",
  }
}

function buildServiceFormValues(initialServiceConfig?: ApplicationServiceConfig): ApplicationServiceFormValues {
  const grouped = new Map<string, ApplicationServiceFormValues["environmentConfigs"][number]["hosts"]>()

  initialServiceConfig?.environmentConfigs?.forEach((config) => {
    const hosts = grouped.get(config.environmentName) ?? []
    hosts.push({
      host: config.host?.replace(/^https?:\/\//i, "") ?? "",
      https: config.https ?? true,
      editing: false,
      prefix: "",
      suffix: "",
    })
    grouped.set(config.environmentName, hosts)
  })

  return {
    port: initialServiceConfig?.port != null ? String(initialServiceConfig.port) : "",
    environmentConfigs: Array.from(grouped.entries()).map(([environmentName, hosts]) => ({
      environmentName,
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
  const [hostErrors, setHostErrors] = useState<Record<string, string>>({})
  const [saving, setSaving] = useState(false)
  const [envsLoading, setEnvsLoading] = useState(!!(namespace && applicationName))
  const [domains, setDomains] = useState<Domain[]>([])
  const [pendingApexConfirm, setPendingApexConfirm] = useState<HostErrorContext | null>(null)
  const { t } = useLanguage()

  const form = useForm<ApplicationServiceFormValues>({
    resolver: zodResolver(applicationServiceSchema),
    defaultValues: buildServiceFormValues(initialServiceConfig),
  })

  const { fields: envFields } = useFieldArray({
    control: form.control,
    name: "environmentConfigs",
  })

  const environmentConfigsWatch = useWatch({ control: form.control, name: "environmentConfigs" })
  const environmentConfigs = useMemo(() => environmentConfigsWatch ?? [], [environmentConfigsWatch])

  const environmentIndexByName = useMemo(() => {
    return new Map(environmentConfigs.map((group, index) => [group.environmentName, index]))
  }, [environmentConfigs])

  const pendingApexSuffix = useMemo(() => {
    if (!pendingApexConfirm) {
      return ""
    }

    const environmentIndex = environmentIndexByName.get(pendingApexConfirm.environmentName)
    if (environmentIndex == null) {
      return ""
    }

    return form.getValues(`environmentConfigs.${environmentIndex}.hosts.${pendingApexConfirm.hostIndex}.suffix`) || ""
  }, [environmentIndexByName, form, pendingApexConfirm])

  const buildSnapshot = useCallback((values: ApplicationServiceFormValues = form.getValues()) => JSON.stringify({
    port: values.port?.trim() ?? "",
    environmentConfigs: (values.environmentConfigs ?? []).map((group) => ({
      environmentName: group.environmentName,
      hosts: group.hosts.map(normalizeHostForSnapshot),
    })),
  }), [form])

  const setHosts = useCallback((environmentName: string, nextHosts: ApplicationServiceFormValues["environmentConfigs"][number]["hosts"]) => {
    const environmentIndex = environmentIndexByName.get(environmentName)
    if (environmentIndex == null) {
      return
    }

    form.setValue(`environmentConfigs.${environmentIndex}.hosts`, nextHosts, {
      shouldDirty: true,
      shouldTouch: true,
    })
  }, [environmentIndexByName, form])

  const updateHost = useCallback((environmentName: string, hostIndex: number, patch: Partial<ApplicationServiceFormValues["environmentConfigs"][number]["hosts"][number]>) => {
    const environmentIndex = environmentIndexByName.get(environmentName)
    if (environmentIndex == null) {
      return
    }

    const hosts = form.getValues(`environmentConfigs.${environmentIndex}.hosts`)
    const nextHosts = hosts.map((host, index) => (
      index === hostIndex ? { ...host, ...patch } : host
    ))
    setHosts(environmentName, nextHosts)
  }, [environmentIndexByName, form, setHosts])

  const hostErrorKey = useCallback((environmentName: string, hostIndex: number) => `${environmentName}::${hostIndex}`, [])

  const clearHostError = useCallback((environmentName: string, hostIndex: number) => {
    const key = hostErrorKey(environmentName, hostIndex)
    setHostErrors((current) => {
      if (!(key in current)) {
        return current
      }

      const next = { ...current }
      delete next[key]
      return next
    })
  }, [hostErrorKey])

  const validateHost = useCallback(async (environmentName: string, hostIndex: number, host: string) => {
    if (!namespace || !applicationName) return

    const trimmedHost = host.trim()
    const key = hostErrorKey(environmentName, hostIndex)
    if (!trimmedHost) {
      clearHostError(environmentName, hostIndex)
      return
    }

    const formatMessage = (name: string, ns: string, env: string) =>
      t("apps.service.hostDuplicated")
        .replace("{name}", name)
        .replace("{namespace}", ns)
        .replace("{environment}", env)

    for (const group of environmentConfigs) {
      for (let index = 0; index < group.hosts.length; index += 1) {
        if (group.environmentName === environmentName && index === hostIndex) {
          continue
        }
        if (group.hosts[index].host.trim() === trimmedHost) {
          setHostErrors((current) => ({
            ...current,
            [key]: formatMessage(applicationName, namespace, group.environmentName),
          }))
          return
        }
      }
    }

    try {
      const result = await checkApplicationServiceHost(namespace, applicationName, trimmedHost)
      const duplicatedHost = result.data
      if (result.success && duplicatedHost) {
        setHostErrors((current) => ({
          ...current,
          [key]: formatMessage(duplicatedHost.applicationName, duplicatedHost.namespace, duplicatedHost.environmentName),
        }))
      } else {
        clearHostError(environmentName, hostIndex)
      }
    } catch {
      // ignore transient errors
    }
  }, [applicationName, clearHostError, environmentConfigs, hostErrorKey, namespace, t])

  const beginEdit = useCallback((environmentName: string, hostIndex: number) => {
    const environmentIndex = environmentIndexByName.get(environmentName)
    if (environmentIndex == null) {
      return
    }

    const host = form.getValues(`environmentConfigs.${environmentIndex}.hosts.${hostIndex}`)
    const { prefix, suffix } = splitHost(host.host, domains)
    updateHost(environmentName, hostIndex, { editing: true, prefix, suffix })
  }, [domains, environmentIndexByName, form, updateHost])

  const confirmEdit = useCallback((environmentName: string, hostIndex: number) => {
    const environmentIndex = environmentIndexByName.get(environmentName)
    if (environmentIndex == null) {
      return
    }

    const host = form.getValues(`environmentConfigs.${environmentIndex}.hosts.${hostIndex}`)
    if (!host.suffix) {
      toast.error(t("apps.service.suffixRequired"))
      return
    }
    if (!host.prefix.trim()) {
      setPendingApexConfirm({ environmentName, hostIndex })
      return
    }

    const combinedHost = combineHost(host.prefix, host.suffix)
    updateHost(environmentName, hostIndex, {
      host: combinedHost,
      editing: false,
      prefix: "",
      suffix: "",
    })
    void validateHost(environmentName, hostIndex, combinedHost)
  }, [environmentIndexByName, form, t, updateHost, validateHost])

  const confirmApexEdit = useCallback(() => {
    if (!pendingApexConfirm) {
      return
    }

    const { environmentName, hostIndex } = pendingApexConfirm
    const environmentIndex = environmentIndexByName.get(environmentName)
    if (environmentIndex == null) {
      setPendingApexConfirm(null)
      return
    }

    const host = form.getValues(`environmentConfigs.${environmentIndex}.hosts.${hostIndex}`)
    const combinedHost = combineHost(host.prefix, host.suffix)
    setPendingApexConfirm(null)
    updateHost(environmentName, hostIndex, {
      host: combinedHost,
      editing: false,
      prefix: "",
      suffix: "",
    })
    void validateHost(environmentName, hostIndex, combinedHost)
  }, [environmentIndexByName, form, pendingApexConfirm, updateHost, validateHost])

  const removeHost = useCallback((environmentName: string, hostIndex: number) => {
    const environmentIndex = environmentIndexByName.get(environmentName)
    if (environmentIndex == null) {
      return
    }

    const hosts = form.getValues(`environmentConfigs.${environmentIndex}.hosts`)
    setHosts(environmentName, hosts.filter((_, index) => index !== hostIndex))
    setHostErrors((current) => {
      const next: Record<string, string> = {}
      Object.entries(current).forEach(([key, message]) => {
        const [envName, indexText] = key.split("::")
        const index = Number(indexText)
        if (envName !== environmentName) {
          next[key] = message
        } else if (index < hostIndex) {
          next[key] = message
        } else if (index > hostIndex) {
          next[`${envName}::${index - 1}`] = message
        }
      })
      return next
    })
  }, [environmentIndexByName, form, setHosts])

  const cancelEdit = useCallback((environmentName: string, hostIndex: number) => {
    const environmentIndex = environmentIndexByName.get(environmentName)
    if (environmentIndex == null) {
      return
    }

    const host = form.getValues(`environmentConfigs.${environmentIndex}.hosts.${hostIndex}`)
    if (!host.host) {
      removeHost(environmentName, hostIndex)
      return
    }

    updateHost(environmentName, hostIndex, { editing: false, prefix: "", suffix: "" })
  }, [environmentIndexByName, form, removeHost, updateHost])

  const addHost = useCallback((environmentName: string) => {
    const environmentIndex = environmentIndexByName.get(environmentName)
    if (environmentIndex == null) {
      return
    }

    const hosts = form.getValues(`environmentConfigs.${environmentIndex}.hosts`)
    setHosts(environmentName, [...hosts, createEmptyHost()])
  }, [environmentIndexByName, form, setHosts])

  useEffect(() => {
    fetchDomains().then(setDomains).catch(() => setDomains([]))
  }, [])

  useEffect(() => {
    const built = buildServiceFormValues(initialServiceConfig)
    const currentConfigs = form.getValues("environmentConfigs") ?? []

    const mergedConfigs = currentConfigs.map((current) => {
      const builtGroup = built.environmentConfigs.find(
        (g) => g.environmentName === current.environmentName
      )
      return {
        environmentName: current.environmentName,
        hosts: builtGroup?.hosts ?? [],
      }
    })

    built.environmentConfigs.forEach((builtGroup) => {
      if (!mergedConfigs.some((c) => c.environmentName === builtGroup.environmentName)) {
        mergedConfigs.push(builtGroup)
      }
    })

    form.reset({
      port: built.port,
      environmentConfigs: mergedConfigs,
    })
  }, [form, initialServiceConfig])

  useEffect(() => {
    if (environmentConfigs.length > 0 && !activeTab) {
      setActiveTab(environmentConfigs[0].environmentName)
    }
  }, [activeTab, environmentConfigs])

  const handleEnvironmentsLoaded = useCallback((environments: ApplicationEnvironment[]) => {
    const currentConfigs = form.getValues("environmentConfigs")
    const nextConfigs = environments.map((environment) => {
      const existing = currentConfigs.find((config) => config.environmentName === environment.environmentName)
      return existing ?? {
        environmentName: environment.environmentName,
        hosts: [],
      }
    })

    form.reset({
      port: form.getValues("port"),
      environmentConfigs: nextConfigs,
    })

    if (nextConfigs.length > 0 && !activeTab) {
      setActiveTab(nextConfigs[0].environmentName)
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

    const hasPending = values.environmentConfigs.some((group) => group.hosts.some((host) => host.editing))
    if (hasPending) {
      toast.error(t("apps.service.confirmPending"))
      return false
    }

    if (Object.keys(hostErrors).length > 0) {
      toast.error(Object.values(hostErrors)[0])
      return false
    }

    const environmentConfigsPayload: ApplicationServiceEnvironmentConfig[] = []
    values.environmentConfigs.forEach((group) => {
      group.hosts.forEach((host) => {
        if (host.host.trim()) {
          environmentConfigsPayload.push({
            environmentName: group.environmentName,
            host: host.host.trim(),
            https: host.https,
          })
        }
      })
    })

    setSaving(true)
    try {
      const result = await updateApplicationService(namespace, applicationName, {
        port: portValue,
        environmentConfigs: environmentConfigsPayload,
      })
      if (!result.success) {
        toast.error(result.message || t("apps.service.saveError"))
        return false
      }

      toast.success(t("apps.service.saveSuccess"))
      onSaved?.({
        port: portValue,
        environmentConfigs: environmentConfigsPayload,
      })
      form.reset(values)
      return true
    } catch {
      toast.error(t("apps.service.saveError"))
      return false
    } finally {
      setSaving(false)
    }
  }, [applicationName, form, hostErrors, namespace, onSaved, t])

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
    <Form {...form}>
      <form onSubmit={handleSubmit} className="flex w-full flex-col gap-6">
        <div className="border rounded-lg overflow-hidden">
          <div className="flex items-center gap-2 px-4 py-3 bg-muted/50 border-b">
            <Network className="h-4 w-4 text-muted-foreground" />
            <span className="text-sm font-semibold">{t("apps.service.accessEntry")}</span>
          </div>
          <div className="flex flex-col gap-4 p-4">
            <div className="grid gap-2">
            <Label htmlFor="service-port" className="flex items-center gap-1">
              <Plug className="h-3.5 w-3.5" />
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

          <Label className="flex items-center gap-1">
            <Globe className="h-3.5 w-3.5" />
            {t("apps.service.hosts")}
          </Label>

          <div className="flex flex-col gap-2">
            {envsLoading && (
              <div className="flex flex-col gap-3">
                <Skeleton className="h-9 w-64" />
                <Skeleton className="h-32 w-full" />
              </div>
            )}

            <div className={envsLoading ? "hidden" : ""}>
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

                  return (
                    <TabsContent key={field.id} value={group.environmentName}>
                      <div className="grid gap-4">
                        {domains.length === 0 ? (
                          <div className="text-sm text-muted-foreground px-3 py-2 border rounded-md border-dashed">
                            {t("apps.service.noDomainPrefix")}
                            <Link href="/networks/domains" className="inline-flex items-center gap-0.5 text-primary mx-1">
                              <span className="hover:underline">{t("apps.service.noDomainLink")}</span>
                              <ExternalLink className="h-3 w-3" />
                            </Link>
                            {t("apps.service.noDomainSuffix")}
                          </div>
                        ) : (
                          group.hosts.map((hostConfig, hostIndex) => {
                            const error = hostErrors[hostErrorKey(group.environmentName, hostIndex)]

                            return (
                              <div key={`${group.environmentName}-${hostIndex}`} className="flex w-full items-start gap-2">
                                {hostConfig.editing && (
                                  <div className="flex h-9 items-center gap-2">
                                    <Checkbox
                                      id={`service-https-${group.environmentName}-${hostIndex}`}
                                      checked={hostConfig.https}
                                      onCheckedChange={(checked) => {
                                        updateHost(group.environmentName, hostIndex, { https: checked === true })
                                      }}
                                    />
                                    <Label htmlFor={`service-https-${group.environmentName}-${hostIndex}`}>HTTPS</Label>
                                  </div>
                                )}

                                <div className="flex flex-1 flex-col gap-1">
                                  {hostConfig.editing ? (
                                    <div className="flex items-center gap-1">
                                      <Input
                                        className="flex-1"
                                        autoComplete="off"
                                        value={hostConfig.prefix}
                                        onChange={(event) => {
                                          updateHost(group.environmentName, hostIndex, {
                                            prefix: event.target.value.trim().toLowerCase(),
                                          })
                                        }}
                                        placeholder={t("apps.service.prefixPlaceholder")}
                                      />
                                      <span className="text-muted-foreground">.</span>
                                      <Select
                                        value={hostConfig.suffix}
                                        onValueChange={(value) => {
                                          updateHost(group.environmentName, hostIndex, { suffix: value })
                                        }}
                                      >
                                        <SelectTrigger className="flex-1">
                                          <SelectValue placeholder={t("apps.service.suffixPlaceholder")}>
                                            {hostConfig.suffix}
                                          </SelectValue>
                                        </SelectTrigger>
                                        <SelectContent>
                                          {domains.map((domain) => (
                                            <SelectItem key={domain.id} value={domain.host}>
                                              <div className="flex flex-col items-start">
                                                <span>{domain.host}</span>
                                                {domain.description && (
                                                  <span className="text-xs text-muted-foreground">{domain.description}</span>
                                                )}
                                              </div>
                                            </SelectItem>
                                          ))}
                                        </SelectContent>
                                      </Select>
                                    </div>
                                  ) : hostConfig.host ? (
                                    <div className="flex h-9 items-center gap-2">
                                      <Copyable
                                        value={`${hostConfig.https ? "https" : "http"}://${hostConfig.host}`}
                                        maxLength={Infinity}
                                      />
                                      <a
                                        href={`${hostConfig.https ? "https" : "http"}://${hostConfig.host}`}
                                        target="_blank"
                                        rel="noopener noreferrer"
                                        className="text-muted-foreground hover:text-foreground transition-colors"
                                      >
                                        <ExternalLink className="h-4 w-4" />
                                      </a>
                                    </div>
                                  ) : (
                                    <span className="h-9 inline-flex items-center text-sm text-muted-foreground">
                                      {t("apps.service.domainPlaceholder")}
                                    </span>
                                  )}
                                  {error && <p className="text-xs text-destructive">{error}</p>}
                                </div>

                                {hostConfig.editing ? (
                                  <Button
                                    type="button"
                                    variant="outline"
                                    size="icon"
                                    aria-label={t("apps.service.confirmHost")}
                                    onClick={() => confirmEdit(group.environmentName, hostIndex)}
                                  >
                                    <Check className="h-4 w-4" />
                                  </Button>
                                ) : (
                                  <Button
                                    type="button"
                                    variant="outline"
                                    size="icon"
                                    aria-label={t("apps.service.editHost")}
                                    onClick={() => beginEdit(group.environmentName, hostIndex)}
                                  >
                                    <Pencil className="h-4 w-4" />
                                  </Button>
                                )}

                                {hostConfig.editing ? (
                                  <Button
                                    type="button"
                                    variant="outline"
                                    size="icon"
                                    aria-label={t("apps.service.cancelHost")}
                                    onClick={() => cancelEdit(group.environmentName, hostIndex)}
                                  >
                                    <X className="h-4 w-4" />
                                  </Button>
                                ) : (
                                  <Button
                                    type="button"
                                    variant="outline"
                                    size="icon"
                                    onClick={() => removeHost(group.environmentName, hostIndex)}
                                  >
                                    <Trash2 className="h-4 w-4 text-destructive" />
                                  </Button>
                                )}
                              </div>
                            )
                          })
                        )}

                        {domains.length > 0 && (
                          <Button
                            type="button"
                            variant="outline"
                            className="w-full"
                            onClick={() => addHost(group.environmentName)}
                          >
                            <Plus className="h-4 w-4 mr-1" />
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
      </div>

      <div>
          <Button type="submit" disabled={saving || !namespace || !applicationName}>
            {saving ? t("common.saving") : t("common.save")}
          </Button>
        </div>

        <AlertDialog open={!!pendingApexConfirm} onOpenChange={(open) => { if (!open) setPendingApexConfirm(null) }}>
          <AlertDialogContent>
            <AlertDialogHeader>
            <AlertDialogTitle>{t("apps.service.apexConfirmTitle")}</AlertDialogTitle>
            <AlertDialogDescription>
                {t("apps.service.apexConfirmDesc").replace("{host}", pendingApexSuffix)}
            </AlertDialogDescription>
          </AlertDialogHeader>
            <AlertDialogFooter>
              <AlertDialogCancel onClick={() => setPendingApexConfirm(null)}>{t("common.cancel")}</AlertDialogCancel>
              <AlertDialogAction onClick={confirmApexEdit}>{t("common.confirm")}</AlertDialogAction>
            </AlertDialogFooter>
          </AlertDialogContent>
        </AlertDialog>
      </form>
    </Form>
  )
})
