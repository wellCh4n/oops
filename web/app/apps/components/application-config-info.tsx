"use client"

import { forwardRef, useCallback, useEffect, useLayoutEffect, useRef, useState } from "react"
import { useForm, useFieldArray } from "react-hook-form"
import { zodResolver } from "@hookform/resolvers/zod"
import { Plus, Trash2, Loader2, Upload, Copy, Container, KeyRound } from "lucide-react"
import { toast } from "sonner"

import { ApplicationEnvironmentSelector } from "./application-environment-selector"
import {
  Form,
  FormControl,
  FormField,
  FormItem,
  FormMessage,
} from "@/components/ui/form"
import { Input } from "@/components/ui/input"
import { Button } from "@/components/ui/button"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import { getApplicationConfigMaps, updateApplicationConfigMaps } from "@/lib/api/applications"
import { ApplicationEnvironment } from "@/lib/api/types"
import { ApplicationConfigFormValues, applicationConfigSchema } from "../schema"
import { useLanguage } from "@/contexts/language-context"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog"
import { Textarea } from "@/components/ui/textarea"
import { Label } from "@/components/ui/label"
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group"
import { EnvImportDialog, parseEnvContent } from "./env-import-dialog"
import { ApplicationTabHandle } from "./application-tab-handle"
import { useApplicationEditorTab } from "./use-application-editor-tab"
import { ApplicationEditorTabSkeleton } from "./application-editor-skeleton"

// Kubernetes env var names must be C identifiers; ConfigMap/Secret keys are more permissive. An env-only
// item (no mount path) whose key fails this is silently dropped by the kubelet, so we warn (non-blocking).
const ENV_NAME_PATTERN = /^[A-Za-z_][A-Za-z0-9_]*$/

function ConfigValueTextarea({
  value,
  onChange,
  disabled,
  masked,
}: {
  value: string
  onChange: (value: string) => void
  disabled?: boolean
  masked?: boolean
}) {
  const ref = useRef<HTMLTextAreaElement | null>(null)

  useLayoutEffect(() => {
    const el = ref.current
    if (!el) return
    el.style.height = "auto"
    el.style.height = `${el.scrollHeight}px`
  }, [value])

  return (
    <textarea
      ref={ref}
      value={value}
      disabled={disabled}
      autoComplete="off"
      placeholder="value"
      rows={1}
      className={`border-input placeholder:text-muted-foreground focus-visible:border-ring focus-visible:ring-ring/50 aria-invalid:ring-destructive/20 dark:aria-invalid:ring-destructive/40 aria-invalid:border-destructive dark:bg-input/30 flex w-full rounded-md border bg-transparent px-3 py-2 text-base shadow-xs outline-none transition-[color,box-shadow,filter] focus-visible:ring-[3px] disabled:cursor-not-allowed disabled:opacity-50 md:text-sm resize-none overflow-hidden${masked && value ? " blur-[4px] hover:blur-none focus:blur-none" : ""}`}
      onChange={(e) => {
        e.currentTarget.style.height = "auto"
        e.currentTarget.style.height = `${e.currentTarget.scrollHeight}px`
        onChange(e.currentTarget.value)
      }}
    />
  )
}

interface ApplicationConfigInfoProps {
  applicationName?: string
  namespace?: string
}

function serializeConfigMaps(configs: ApplicationConfigFormValues["configMaps"] = []) {
  return JSON.stringify(
    (configs ?? []).map((config) => ({
      key: config.key ?? "",
      value: config.value ?? "",
      secret: config.secret ?? false,
      mountPath: config.mountPath ?? "",
    }))
  )
}

export const ApplicationConfigInfo = forwardRef<ApplicationTabHandle, ApplicationConfigInfoProps>(function ApplicationConfigInfo({
  applicationName,
  namespace,
}: ApplicationConfigInfoProps, ref) {
  // Removed environments state as it's handled by selector
  const [activeTab, setActiveTab] = useState<string>("")
  const [isLoadingConfig, setIsLoadingConfig] = useState(false)
  const [isSaving, setIsSaving] = useState(false)
  const [envsLoading, setEnvsLoading] = useState(!!(namespace && applicationName))
  const { t } = useLanguage()

  // Import dialog state
  const [importInputDialogOpen, setImportInputDialogOpen] = useState(false)
  const [importConfirmDialogOpen, setImportConfirmDialogOpen] = useState(false)
  const [importContent, setImportContent] = useState("")
  const [importMode, setImportMode] = useState<"key-only" | "key-value">("key-value")

  const form = useForm<ApplicationConfigFormValues>({
    resolver: zodResolver(applicationConfigSchema),
    defaultValues: {
      configMaps: [],
    },
  })

  const { fields, append, remove } = useFieldArray({
    control: form.control,
    name: "configMaps",
  })

  // Each field carries its absolute index so FormField names and remove() stay correct after filtering.
  const indexedFields = fields.map((field, index) => ({ field, index }))
  const configMapFields = indexedFields.filter(({ field }) => !field.secret)
  const secretFields = indexedFields.filter(({ field }) => field.secret)

  const buildSnapshot = useCallback(
    (configs = form.getValues("configMaps")) => serializeConfigMaps(configs),
    [form]
  )

  const handleEnvironmentsLoaded = (envs: ApplicationEnvironment[]) => {
    if (envs.length > 0 && !activeTab) {
      setActiveTab(envs[0].environmentName)
    }
  }

  async function onSubmit(data: ApplicationConfigFormValues) {
    if (!namespace || !applicationName || !activeTab) return false

    setIsSaving(true)
    try {
      await updateApplicationConfigMaps(namespace, applicationName, activeTab, data.configMaps)
      toast.success(t("apps.config.updateSuccess"))
      form.reset(data)
      return true
    } catch (error) {
      toast.error(t("apps.config.updateError"))
      console.error(error)
      return false
    } finally {
      setIsSaving(false)
    }
  }

  const saveCurrentTab = async () => {
    let success = false
    await form.handleSubmit(async (data) => {
      success = await onSubmit(data)
    })()
    return success
  }

  const { captureBaseline, handleSubmit } = useApplicationEditorTab({
    ref,
    form,
    isReady: !(envsLoading || isLoadingConfig),
    getSnapshot: buildSnapshot,
    onSave: saveCurrentTab,
    onSubmit,
  })

  useEffect(() => {
    const fetchConfigMaps = async () => {
      if (!namespace || !applicationName || !activeTab) return

      setIsLoadingConfig(true)
      try {
        const res = await getApplicationConfigMaps(namespace, applicationName, activeTab)
        if (res.data) {
          form.reset({ configMaps: res.data })
        } else {
          form.reset({ configMaps: [] })
        }
        captureBaseline()
      } catch (error) {
        toast.error(t("apps.config.fetchError"))
        console.error(error)
      } finally {
        setIsLoadingConfig(false)
      }
    }

    fetchConfigMaps()
  }, [activeTab, applicationName, captureBaseline, form, namespace, t])

  const handleImportConfirm = (result: {
    toAdd: { key: string; value: string }[]
    toReplace: { old: { key: string; value: string }; new: { key: string; value: string } }[]
  }) => {
    const currentConfigs = form.getValues("configMaps")
    const newConfigs = [...currentConfigs]
    // Import only targets ConfigMap (non-secret) env entries.
    const indexByKey = new Map<string, number>()
    newConfigs.forEach((config, index) => {
      if (!config.secret) {
        indexByKey.set(config.key, index)
      }
    })

    for (const item of result.toAdd) {
      if (!indexByKey.has(item.key)) {
        indexByKey.set(item.key, newConfigs.length)
        newConfigs.push({ ...item, secret: false, mountPath: "" })
      }
    }

    for (const item of result.toReplace) {
      const index = indexByKey.get(item.old.key)
      if (index !== undefined) {
        newConfigs[index] = { ...newConfigs[index], ...item.new }
      }
    }

    form.setValue("configMaps", newConfigs)
    toast.success(t("apps.config.importSuccess"))
  }

  const handleImportSubmit = () => {
    const parsed = parseEnvContent(importContent)
    if (parsed.length === 0) {
      toast.error(t("apps.config.importNoValid"))
      return
    }
    setImportInputDialogOpen(false)
    setImportConfirmDialogOpen(true)
  }

  const handleExportAll = async () => {
    // Export ConfigMap (non-secret) entries only; secrets are never copied to the clipboard.
    const configs = form.getValues("configMaps").filter((c) => !c.secret)
    const envContent = configs
      .map(c => {
        if (c.value.includes(" ") || c.value.includes("\n") || c.value.includes('"')) {
          return `${c.key}="${c.value.replace(/"/g, '\\"')}"`
        }
        return `${c.key}=${c.value}`
      })
      .join("\n")

    try {
      await navigator.clipboard.writeText(envContent)
      toast.success(t("apps.config.copySuccess"))
    } catch {
      toast.error(t("apps.config.copyError"))
    }
  }

  const parsedImportContent = parseEnvContent(importContent)

  const renderSection = (
    rows: { field: (typeof fields)[number]; index: number }[],
    isSecret: boolean
  ) => (
    <div className="rounded-md border">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead className="w-[28%]">Key</TableHead>
            <TableHead className="w-[34%]">Value</TableHead>
            <TableHead className="w-[30%]">{t("apps.config.mountPath")}</TableHead>
            <TableHead className="w-[8%]"></TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {rows.map(({ field, index }) => (
            <TableRow key={field.id}>
              <TableCell>
                <FormField
                  control={form.control}
                  name={`configMaps.${index}.key`}
                  render={({ field }) => {
                    const mountPath = form.watch(`configMaps.${index}.mountPath`)
                    const showEnvNameWarning =
                      !!field.value && !mountPath?.trim() && !ENV_NAME_PATTERN.test(field.value)
                    return (
                      <FormItem>
                        <FormControl>
                          <Input autoComplete="off" placeholder="key" {...field} />
                        </FormControl>
                        {showEnvNameWarning && (
                          <p className="text-xs text-amber-600">{t("apps.config.envNameWarning")}</p>
                        )}
                        <FormMessage />
                      </FormItem>
                    )
                  }}
                />
              </TableCell>
              <TableCell>
                <FormField
                  control={form.control}
                  name={`configMaps.${index}.value`}
                  render={({ field }) => (
                    <FormItem>
                      <FormControl>
                        <ConfigValueTextarea
                          value={(field.value ?? "") as string}
                          disabled={field.disabled}
                          masked={isSecret}
                          onChange={field.onChange}
                        />
                      </FormControl>
                      <FormMessage />
                    </FormItem>
                  )}
                />
              </TableCell>
              <TableCell>
                <FormField
                  control={form.control}
                  name={`configMaps.${index}.mountPath`}
                  render={({ field }) => (
                    <FormItem>
                      <FormControl>
                        <Input
                          autoComplete="off"
                          placeholder={t("apps.config.mountPathPlaceholder")}
                          className="font-mono text-xs"
                          {...field}
                          value={field.value ?? ""}
                        />
                      </FormControl>
                      <FormMessage />
                    </FormItem>
                  )}
                />
              </TableCell>
              <TableCell>
                <Button
                  type="button"
                  variant="ghost"
                  size="icon"
                  className="cursor-pointer"
                  onClick={() => remove(index)}
                >
                  <Trash2 className="size-4 text-destructive" />
                </Button>
              </TableCell>
            </TableRow>
          ))}
          {rows.length === 0 && (
            <TableRow>
              <TableCell colSpan={4} className="text-center text-muted-foreground h-12">
                {isLoadingConfig ? t("common.loading") : t("apps.config.noConfig")}
              </TableCell>
            </TableRow>
          )}
          <TableRow>
            <TableCell colSpan={4}>
              <Button
                type="button"
                variant="outline"
                className="w-full justify-center cursor-pointer"
                onClick={() => append({ key: "", value: "", secret: isSecret, mountPath: "" })}
                disabled={isLoadingConfig}
              >
                <Plus className="size-4" />
                {t("apps.config.addItem")}
              </Button>
            </TableCell>
          </TableRow>
        </TableBody>
      </Table>
    </div>
  )

  return (
    <>
      {envsLoading && <ApplicationEditorTabSkeleton />}
      <div className={envsLoading ? "hidden" : "w-full"}>
        <Form {...form}>
        <form onSubmit={handleSubmit} className="flex w-full flex-col gap-4">
        <div className="border rounded-lg overflow-hidden">
          <div className="flex items-center gap-2 px-4 py-3 bg-muted/50 border-b">
            <Container className="size-4 text-muted-foreground" />
            <span className="text-sm font-semibold">{t("apps.config.title")}</span>
          </div>
          <div className="flex flex-col gap-6 p-4">
            <ApplicationEnvironmentSelector
              namespace={namespace}
              applicationName={applicationName}
              value={activeTab}
              onValueChange={setActiveTab}
              onEnvironmentsLoaded={handleEnvironmentsLoaded}
              onLoadingChange={setEnvsLoading}
            >
              {/* ConfigMap section */}
              <div className="flex flex-col gap-3">
                <div className="flex items-center gap-2">
                  <Container className="size-4 text-muted-foreground" />
                  <span className="text-sm font-semibold">{t("apps.config.configMapSection")}</span>
                  <span className="text-xs text-muted-foreground">{t("apps.config.configMapHint")}</span>
                </div>
                {renderSection(configMapFields, false)}

                <div className="flex gap-2">
                  <Button
                    type="button"
                    variant="outline"
                    className="cursor-pointer"
                    onClick={handleExportAll}
                    disabled={isLoadingConfig}
                  >
                    <Copy className="size-4" />
                    {t("apps.config.copyAll")}
                  </Button>
                  <Dialog open={importInputDialogOpen} onOpenChange={setImportInputDialogOpen}>
                    <DialogTrigger asChild>
                      <Button
                        type="button"
                        variant="outline"
                        className="cursor-pointer"
                        disabled={isLoadingConfig}
                      >
                        <Upload className="size-4" />
                        {t("apps.config.import")}
                      </Button>
                    </DialogTrigger>
                    <DialogContent className="max-w-3xl flex flex-col max-h-[90vh] overflow-hidden">
                      <DialogHeader>
                        <DialogTitle>{t("apps.config.importTitle")}</DialogTitle>
                        <DialogDescription>
                          {t("apps.config.importDesc")}
                        </DialogDescription>
                      </DialogHeader>
                      <div className="flex flex-col flex-1 min-h-0 overflow-y-auto">
                        <div className="flex items-center gap-6 mb-4">
                          <span className="text-sm font-medium">{t("apps.config.importMode")}:</span>
                          <RadioGroup
                            value={importMode}
                            onValueChange={(v) => setImportMode(v as "key-only" | "key-value")}
                            className="flex gap-4"
                          >
                            <div className="flex items-center gap-2">
                              <RadioGroupItem value="key-value" id="key-value" />
                              <Label htmlFor="key-value" className="cursor-pointer">
                                {t("apps.config.importKeyValue")}
                              </Label>
                            </div>
                            <div className="flex items-center gap-2">
                              <RadioGroupItem value="key-only" id="key-only" />
                              <Label htmlFor="key-only" className="cursor-pointer">
                                {t("apps.config.importKeyOnly")}
                              </Label>
                            </div>
                          </RadioGroup>
                        </div>
                        <Textarea
                          id="import-content"
                          autoComplete="off"
                          placeholder={"KEY=value\nKEY2=value2\n# comment\nKEY3="}
                          value={importContent}
                          onChange={(e) => setImportContent(e.target.value)}
                          className="font-mono text-sm flex-1 min-h-[300px] max-h-[60vh]"
                        />
                      </div>
                      <DialogFooter>
                        <Button
                          variant="outline"
                          className="cursor-pointer"
                          onClick={() => setImportInputDialogOpen(false)}
                        >
                          {t("common.cancel")}
                        </Button>
                        <Button className="cursor-pointer" onClick={handleImportSubmit}>
                          {t("apps.config.previewImport")}
                        </Button>
                      </DialogFooter>
                    </DialogContent>
                  </Dialog>
                </div>
              </div>

              {/* Secret section */}
              <div className="flex flex-col gap-3">
                <div className="flex items-center gap-2">
                  <KeyRound className="size-4 text-muted-foreground" />
                  <span className="text-sm font-semibold">{t("apps.config.secretSection")}</span>
                  <span className="text-xs text-muted-foreground">{t("apps.config.secretHint")}</span>
                </div>
                {renderSection(secretFields, true)}
              </div>
            </ApplicationEnvironmentSelector>
          </div>
        </div>

        <div className="flex">
          <Button type="submit" className="cursor-pointer" disabled={isSaving || isLoadingConfig}>
            {isSaving && <Loader2 className="size-4 animate-spin" />}
            {t("common.save")}
          </Button>
        </div>
        </form>
      </Form>
      </div>

    <EnvImportDialog
      open={importConfirmDialogOpen}
      onOpenChange={setImportConfirmDialogOpen}
      currentConfigs={form.getValues("configMaps").filter((c) => !c.secret)}
      parsedEnvContent={parsedImportContent}
      importMode={importMode}
      onConfirm={handleImportConfirm}
    />
    </>
  )
})
