"use client"

import { forwardRef, useEffect, useImperativeHandle, useLayoutEffect, useRef, useState } from "react"
import { useForm, useFieldArray } from "react-hook-form"
import { zodResolver } from "@hookform/resolvers/zod"
import { Plus, Trash2, Loader2, Upload, Copy } from "lucide-react"
import { toast } from "sonner"

import { ApplicationEnvironmentSelector } from "./application-environment-selector"
import { Skeleton } from "@/components/ui/skeleton"
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

function ConfigValueTextarea({
  value,
  onChange,
  disabled,
}: {
  value: string
  onChange: (value: string) => void
  disabled?: boolean
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
      placeholder="value"
      rows={1}
      className="border-input placeholder:text-muted-foreground focus-visible:border-ring focus-visible:ring-ring/50 aria-invalid:ring-destructive/20 dark:aria-invalid:ring-destructive/40 aria-invalid:border-destructive dark:bg-input/30 flex w-full rounded-md border bg-transparent px-3 py-2 text-base shadow-xs outline-none transition-[color,box-shadow] focus-visible:ring-[3px] disabled:cursor-not-allowed disabled:opacity-50 md:text-sm resize-none overflow-hidden"
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
  const baselineRef = useRef("")

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

  const buildSnapshot = (configs = form.getValues("configMaps")) => serializeConfigMaps(configs)

  const handleEnvironmentsLoaded = (envs: ApplicationEnvironment[]) => {
    if (envs.length > 0 && !activeTab) {
      setActiveTab(envs[0].environmentName)
    }
  }

  useEffect(() => {
    const fetchConfigMaps = async () => {
      if (!namespace || !applicationName || !activeTab) return

      setIsLoadingConfig(true)
      try {
        const res = await getApplicationConfigMaps(namespace, applicationName, activeTab)
        if (res.data) {
          form.reset({ configMaps: res.data })
          baselineRef.current = serializeConfigMaps(res.data)
        } else {
          form.reset({ configMaps: [] })
          baselineRef.current = serializeConfigMaps([])
        }
      } catch (error) {
        toast.error(t("apps.config.fetchError"))
        console.error(error)
      } finally {
        setIsLoadingConfig(false)
      }
    }

    fetchConfigMaps()
  }, [activeTab, applicationName, form, namespace, t])

  const onSubmit = async (data: ApplicationConfigFormValues) => {
    if (!namespace || !applicationName || !activeTab) return false

    setIsSaving(true)
    try {
      await updateApplicationConfigMaps(namespace, applicationName, activeTab, data.configMaps)
      toast.success(t("apps.config.updateSuccess"))
      form.reset(data)
      baselineRef.current = buildSnapshot(data.configMaps)
      return true
    } catch (error) {
      toast.error(t("apps.config.updateError"))
      console.error(error)
      return false
    } finally {
      setIsSaving(false)
    }
  }

  const handleImportConfirm = (result: {
    toAdd: { key: string; value: string }[]
    toReplace: { old: { key: string; value: string }; new: { key: string; value: string } }[]
  }) => {
    const currentConfigs = form.getValues("configMaps")
    const newConfigs = [...currentConfigs]

    // 添加新配置
    for (const item of result.toAdd) {
      if (!newConfigs.some(c => c.key === item.key)) {
        newConfigs.push(item)
      }
    }

    // 替换冲突配置
    for (const item of result.toReplace) {
      const index = newConfigs.findIndex(c => c.key === item.old.key)
      if (index !== -1) {
        newConfigs[index] = item.new
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
    const configs = form.getValues("configMaps")
    const envContent = configs
      .map(c => {
        // 如果值包含特殊字符，需要加引号
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

  useImperativeHandle(ref, () => ({
    hasUnsavedChanges() {
      if (envsLoading || isLoadingConfig) {
        return false
      }
      return buildSnapshot() !== baselineRef.current
    },
    async save() {
      if (envsLoading || isLoadingConfig) {
        return true
      }

      let success = false
      await form.handleSubmit(async (data) => {
        success = await onSubmit(data)
      })()
      return success
    },
  }))

  return (
    <div className="flex flex-col gap-6">
      {envsLoading && (
        <div className="flex flex-col gap-3">
          <Skeleton className="h-9 w-64" />
          <Skeleton className="h-48 w-full" />
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
        >
          <Form {...form}>
            <form onSubmit={form.handleSubmit(async (data) => { await onSubmit(data) })} className="flex flex-col gap-4">
              <div className="rounded-md border">
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead className="w-[45%]">Key</TableHead>
                      <TableHead className="w-[45%]">Value</TableHead>
                      <TableHead className="w-[10%]"></TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {fields.map((field, index) => (
                      <TableRow key={field.id}>
                        <TableCell>
                          <FormField
                            control={form.control}
                            name={`configMaps.${index}.key`}
                            render={({ field }) => (
                              <FormItem>
                                <FormControl>
                                  <Input placeholder="key" {...field} />
                                </FormControl>
                                <FormMessage />
                              </FormItem>
                            )}
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
                                    onChange={field.onChange}
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
                            onClick={() => remove(index)}
                          >
                            <Trash2 className="h-4 w-4 text-destructive" />
                          </Button>
                        </TableCell>
                      </TableRow>
                    ))}
                    {fields.length === 0 && (
                      <TableRow>
                        <TableCell colSpan={3} className="text-center text-muted-foreground h-12">
                          {isLoadingConfig ? t("common.loading") : t("apps.config.noConfig")}
                        </TableCell>
                      </TableRow>
                    )}
                    <TableRow>
                      <TableCell colSpan={3}>
                        <Button
                          type="button"
                          variant="outline"
                          className="w-full justify-center"
                          onClick={() => append({ key: "", value: "" })}
                          disabled={isLoadingConfig}
                        >
                          <Plus className="h-4 w-4" />
                          {t("apps.config.addItem")}
                        </Button>
                      </TableCell>
                    </TableRow>
                  </TableBody>
                </Table>
              </div>

              <div className="flex gap-2">
                <Button
                  type="button"
                  variant="outline"
                  onClick={handleExportAll}
                  disabled={isLoadingConfig}
                >
                  <Copy className="h-4 w-4" />
                  {t("apps.config.copyAll")}
                </Button>
                <Dialog open={importInputDialogOpen} onOpenChange={setImportInputDialogOpen}>
                  <DialogTrigger asChild>
                    <Button
                      type="button"
                      variant="outline"
                      disabled={isLoadingConfig}
                    >
                      <Upload className="h-4 w-4" />
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
                          <div className="flex items-center space-x-2">
                            <RadioGroupItem value="key-value" id="key-value" />
                            <Label htmlFor="key-value" className="cursor-pointer">
                              {t("apps.config.importKeyValue")}
                            </Label>
                          </div>
                          <div className="flex items-center space-x-2">
                            <RadioGroupItem value="key-only" id="key-only" />
                            <Label htmlFor="key-only" className="cursor-pointer">
                              {t("apps.config.importKeyOnly")}
                            </Label>
                          </div>
                        </RadioGroup>
                      </div>
                      <Textarea
                        id="import-content"
                        placeholder={"KEY=value\nKEY2=value2\n# comment\nKEY3="}
                        value={importContent}
                        onChange={(e) => setImportContent(e.target.value)}
                        className="font-mono text-sm flex-1 min-h-[300px] max-h-[60vh]"
                      />
                    </div>
                    <DialogFooter>
                      <Button
                        variant="outline"
                        onClick={() => setImportInputDialogOpen(false)}
                      >
                        {t("common.cancel")}
                      </Button>
                      <Button onClick={handleImportSubmit}>
                        {t("apps.config.previewImport")}
                      </Button>
                    </DialogFooter>
                  </DialogContent>
                </Dialog>

                <Button type="submit" disabled={isSaving || isLoadingConfig}>
                  {isSaving && <Loader2 className="h-4 w-4 animate-spin" />}
                  {t("common.save")}
                </Button>
              </div>
            </form>
          </Form>
        </ApplicationEnvironmentSelector>
      </div>

      <EnvImportDialog
        open={importConfirmDialogOpen}
        onOpenChange={setImportConfirmDialogOpen}
        currentConfigs={form.getValues("configMaps")}
        parsedEnvContent={parsedImportContent}
        importMode={importMode}
        onConfirm={handleImportConfirm}
      />
    </div>
  )
})
