"use client"

import { forwardRef, useCallback, useEffect, useLayoutEffect, useRef, useState } from "react"
import { useForm, useFieldArray, type UseFormReturn } from "react-hook-form"
import { zodResolver } from "@hookform/resolvers/zod"
import { Plus, Trash2, Loader2, Upload, Copy, Container, KeyRound, ChevronDown, Search, GripVertical, Download, FileUp, FileDown, HardDrive, FileText, Variable, Info, MessageSquare, Tags, Hash } from "lucide-react"
import { toast } from "sonner"
import {
  DndContext,
  closestCenter,
  PointerSensor,
  useSensor,
  useSensors,
  type DragEndEvent,
} from "@dnd-kit/core"
import {
  SortableContext,
  verticalListSortingStrategy,
  useSortable,
} from "@dnd-kit/sortable"
import { CSS } from "@dnd-kit/utilities"

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
import { getApplicationConfigMaps, updateApplicationConfigMaps } from "@/lib/api/applications"
import { ApplicationEnvironment, ConfigMap } from "@/lib/api/types"
import { ApplicationConfigFormValues, applicationConfigSchema } from "../schema"
import { useLanguage } from "@/contexts/language-context"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Textarea } from "@/components/ui/textarea"
import { Label } from "@/components/ui/label"
import {
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from "@/components/ui/tooltip"
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group"
import { EnvImportDialog, parseEnvContent } from "./env-import-dialog"
import { parseYamlConfig, serializeYamlConfig } from "./config-yaml"
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
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
      placeholder="Value"
      rows={1}
      className={`border-input placeholder:text-muted-foreground focus-visible:border-ring focus-visible:ring-ring/50 aria-invalid:ring-destructive/20 dark:aria-invalid:ring-destructive/40 aria-invalid:border-destructive dark:bg-input/30 flex min-h-9 w-full rounded-md border bg-transparent px-3 py-1.5 text-base shadow-xs outline-none transition-[color,box-shadow,filter] focus-visible:ring-[3px] disabled:cursor-not-allowed disabled:opacity-50 md:text-sm resize-none overflow-hidden${masked && value ? " blur-[4px] hover:blur-none focus:blur-none" : ""}`}
      onChange={(e) => {
        e.currentTarget.style.height = "auto"
        e.currentTarget.style.height = `${e.currentTarget.scrollHeight}px`
        onChange(e.currentTarget.value)
      }}
    />
  )
}

// Shared grid template for the header and every row so columns stay aligned across groups.
const CONFIG_GRID = "grid grid-cols-[24px_minmax(0,1fr)_minmax(0,1.5fr)_auto] items-start gap-3"

// Sentinel tab that shows every item regardless of group. Distinct from the ungrouped bucket ("").
const ALL_GROUPS = "__all__"

// A single draggable config row. Lives at module scope because it uses the useSortable hook, which cannot
// run inside a render callback. It reads/writes form state by absolute index so reordering and grouping map
// straight back to the field array.
function SortableConfigRow({
  id,
  index,
  form,
  isSecret,
  knownGroups,
  onRemove,
  onSetGroup,
  t,
}: {
  id: string
  index: number
  form: UseFormReturn<ApplicationConfigFormValues>
  isSecret: boolean
  knownGroups: string[]
  onRemove: (index: number) => void
  onSetGroup: (index: number, group: string) => void
  t: (key: string) => string
}) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({ id })
  const [mountOpen, setMountOpen] = useState(false)
  const [commentOpen, setCommentOpen] = useState(false)
  const [groupOpen, setGroupOpen] = useState(false)
  const [groupFocused, setGroupFocused] = useState(false)
  // Dialog drafts: edits stay local until "confirm"; cancel / X / outside-click discards them.
  const [mountDraft, setMountDraft] = useState("")
  const [commentDraft, setCommentDraft] = useState("")
  const [groupDraft, setGroupDraft] = useState("")

  const style = {
    // Only translate — not CSS.Transform, which would also apply scaleX/scaleY for the height-transition
    // animation and squash rows that are taller (e.g. carry an inline comment) during drag.
    transform: CSS.Translate.toString(transform),
    transition,
    zIndex: isDragging ? 10 : undefined,
  }

  const currentGroup = form.watch(`configMaps.${index}.group`)?.trim() ?? ""
  const currentGroupValue = form.watch(`configMaps.${index}.group`) ?? ""
  const currentMountPath = form.watch(`configMaps.${index}.mountPath`)
  const currentComment = form.watch(`configMaps.${index}.comment`)
  const isMounted = form.watch(`configMaps.${index}.mounted`)

  return (
    <div
      ref={setNodeRef}
      style={style}
      className={`${CONFIG_GRID} rounded-md border bg-card px-2 py-2 ${isDragging ? "shadow-lg" : ""}`}
    >
      {/* Drag handle */}
      <button
        type="button"
        className="mt-1.5 flex h-6 w-6 items-center justify-center text-muted-foreground/50 hover:text-foreground cursor-grab active:cursor-grabbing touch-none"
        {...attributes}
        {...listeners}
        aria-label={t("apps.config.dragHandle")}
      >
        <GripVertical className="size-4" />
      </button>

      {/* Key + env-name warning */}
      <div>
        <FormField
          control={form.control}
          name={`configMaps.${index}.key`}
          render={({ field }) => {
            const showEnvNameWarning =
              !!field.value && !isMounted && !ENV_NAME_PATTERN.test(field.value)
            return (
              <FormItem>
                <FormControl>
                  <div className="relative">
                    {/* Leading type indicator: file icon when mounted as a file, variable icon otherwise. */}
                    <span
                      className="pointer-events-none absolute left-2.5 top-1/2 -translate-y-1/2 text-muted-foreground"
                      title={isMounted ? t("apps.config.mountAsFile") : t("apps.config.typeEnvVar")}
                    >
                      {isMounted ? <FileText className="size-3.5" /> : <Variable className="size-3.5" />}
                    </span>
                    <Input
                      autoComplete="off"
                      placeholder="Key"
                      className="pl-8"
                      {...field}
                    />
                  </div>
                </FormControl>
                {showEnvNameWarning && (
                  <p className="mt-1 text-xs text-warning">{t("apps.config.envNameWarning")}</p>
                )}
                <FormMessage />
              </FormItem>
            )
          }}
        />
      </div>

      {/* Value */}
      <div>
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
      </div>

      {/* Actions: mount / comment / group dialogs + delete. Each dialog edits a local draft; changes are
          committed only on "confirm" — closing via X or clicking outside discards them. */}
      <div className="flex items-center gap-0">
        {/* Mount */}
        <Tooltip>
          <TooltipTrigger asChild>
            <Button
              type="button"
              variant="ghost"
              size="icon"
              aria-label={t("apps.config.mountAsFile")}
              className={`size-8 cursor-pointer ${isMounted ? "text-primary hover:text-primary" : ""}`}
              onClick={() => {
                setMountDraft(currentMountPath ?? "")
                setMountOpen(true)
              }}
            >
              <HardDrive className="size-4" />
            </Button>
          </TooltipTrigger>
          <TooltipContent>{t("apps.config.mountAsFile")}</TooltipContent>
        </Tooltip>
        <Dialog open={mountOpen} onOpenChange={setMountOpen}>
          <DialogContent className="max-w-md">
            <DialogHeader>
              <DialogTitle>{t("apps.config.mountPath")}</DialogTitle>
            </DialogHeader>
            <div className="flex flex-col gap-2 py-2">
              <Input
                autoComplete="off"
                placeholder={t("apps.config.mountPathPlaceholder")}
                className="font-mono text-xs"
                value={mountDraft}
                onChange={(event) => setMountDraft(event.target.value)}
              />
              <p className="text-xs text-muted-foreground">{t("apps.config.mountPopoverHint")}</p>
            </div>
            <DialogFooter>
              <Button type="button" variant="outline" className="cursor-pointer" onClick={() => setMountOpen(false)}>
                {t("common.cancel")}
              </Button>
              <Button
                type="button"
                className="cursor-pointer"
                onClick={() => {
                  const path = mountDraft
                  // A non-blank path makes it a file mount; empty reverts to an env var.
                  form.setValue(`configMaps.${index}.mountPath`, path, { shouldDirty: true })
                  form.setValue(`configMaps.${index}.mounted`, path.trim().length > 0, { shouldDirty: true })
                  setMountOpen(false)
                }}
              >
                {t("common.confirm")}
              </Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>

        {/* Comment */}
        <Tooltip>
          <TooltipTrigger asChild>
            <Button
              type="button"
              variant="ghost"
              size="icon"
              aria-label={t("apps.config.comment")}
              className={`size-8 cursor-pointer ${currentComment?.trim() ? "text-primary hover:text-primary" : ""}`}
              onClick={() => {
                setCommentDraft(currentComment ?? "")
                setCommentOpen(true)
              }}
            >
              <MessageSquare className="size-4" />
            </Button>
          </TooltipTrigger>
          <TooltipContent>{t("apps.config.comment")}</TooltipContent>
        </Tooltip>
        <Dialog open={commentOpen} onOpenChange={setCommentOpen}>
          <DialogContent className="max-w-md">
            <DialogHeader>
              <DialogTitle>{t("apps.config.comment")}</DialogTitle>
            </DialogHeader>
            <div className="py-2">
              <Textarea
                autoComplete="off"
                placeholder={t("apps.config.commentPlaceholder")}
                className="min-h-24 text-xs"
                value={commentDraft}
                onChange={(event) => setCommentDraft(event.target.value)}
              />
            </div>
            <DialogFooter>
              <Button type="button" variant="outline" className="cursor-pointer" onClick={() => setCommentOpen(false)}>
                {t("common.cancel")}
              </Button>
              <Button
                type="button"
                className="cursor-pointer"
                onClick={() => {
                  form.setValue(`configMaps.${index}.comment`, commentDraft, { shouldDirty: true })
                  setCommentOpen(false)
                }}
              >
                {t("common.confirm")}
              </Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>

        {/* Group */}
        <Tooltip>
          <TooltipTrigger asChild>
            <Button
              type="button"
              variant="ghost"
              size="icon"
              aria-label={t("apps.config.group")}
              className={`size-8 cursor-pointer ${currentGroup ? "text-primary hover:text-primary" : ""}`}
              onClick={() => {
                setGroupDraft(currentGroupValue)
                setGroupOpen(true)
              }}
            >
              <Tags className="size-4" />
            </Button>
          </TooltipTrigger>
          <TooltipContent>{t("apps.config.group")}</TooltipContent>
        </Tooltip>
        <Dialog open={groupOpen} onOpenChange={setGroupOpen}>
          <DialogContent className="max-w-md">
            <DialogHeader>
              <DialogTitle>{t("apps.config.group")}</DialogTitle>
            </DialogHeader>
            <div className="relative py-2">
              <Input
                autoComplete="off"
                placeholder={t("apps.config.groupPlaceholder")}
                value={groupDraft}
                onChange={(event) => setGroupDraft(event.target.value)}
                onFocus={() => setGroupFocused(true)}
                onBlur={() => setGroupFocused(false)}
              />
              {/* Suggestions: existing groups matching the typed text. Typing a name with no match keeps that
                  value — it becomes a new group on confirm. */}
              {groupFocused &&
                (() => {
                  const query = groupDraft.trim().toLowerCase()
                  const matches = knownGroups.filter(
                    (group) => group.toLowerCase() !== query && (!query || group.toLowerCase().includes(query))
                  )
                  if (matches.length === 0) return null
                  return (
                    <div className="absolute inset-x-0 top-full z-50 mt-1 max-h-48 overflow-auto rounded-md border bg-popover p-1 shadow-md">
                      {matches.map((group) => (
                        <button
                          key={group}
                          type="button"
                          className="flex w-full items-center rounded-sm px-2 py-1.5 text-sm hover:bg-accent cursor-pointer"
                          onMouseDown={(event) => {
                            // mousedown (before blur) so the click registers before the list unmounts.
                            event.preventDefault()
                            setGroupDraft(group)
                            setGroupFocused(false)
                          }}
                        >
                          {group}
                        </button>
                      ))}
                    </div>
                  )
                })()}
            </div>
            <DialogFooter>
              <Button type="button" variant="outline" className="cursor-pointer" onClick={() => setGroupOpen(false)}>
                {t("common.cancel")}
              </Button>
              <Button
                type="button"
                className="cursor-pointer"
                onClick={() => {
                  // Commit + regroup so the row moves to its tab.
                  onSetGroup(index, groupDraft.trim())
                  setGroupOpen(false)
                }}
              >
                {t("common.confirm")}
              </Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>

        {/* Delete */}
        <Button
          type="button"
          variant="ghost"
          size="icon"
          className="size-8 cursor-pointer"
          onClick={() => onRemove(index)}
        >
          <Trash2 className="size-4 text-destructive" />
        </Button>
      </div>

      {/* Inline comment: dim, small, prefixed with a "#" icon, spanning the key + value columns. */}
      {currentComment?.trim() && (
        <div className="col-start-2 col-span-2 -mt-1 flex items-start gap-1 text-muted-foreground/70">
          <Hash className="mt-0.5 size-3 shrink-0" />
          <span className="text-xs whitespace-pre-wrap break-words">{currentComment}</span>
        </div>
      )}
    </div>
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
      // Effective mount path: only an explicitly-mounted item contributes one. This keeps dirty detection
      // in sync with the mount toggle and matches what onSubmit sends.
      mountPath: config.mounted ? (config.mountPath ?? "") : "",
      group: config.group?.trim() ?? "",
      comment: config.comment?.trim() ?? "",
    }))
  )
}

// Maps API items into form values, deriving the form-only `mounted` flag from the presence of a mount path.
function toFormConfigMaps(items: ConfigMap[]): ApplicationConfigFormValues["configMaps"] {
  return items.map((item) => ({
    key: item.key ?? "",
    value: item.value ?? "",
    secret: item.secret ?? false,
    mounted: !!item.mountPath?.trim(),
    mountPath: item.mountPath ?? "",
    group: item.group ?? "",
    comment: item.comment ?? "",
  }))
}

// Builds the API payload: drops the form-only `mounted` flag and blanks the mount path for env-only items.
function toApiConfigMaps(configs: ApplicationConfigFormValues["configMaps"]): ConfigMap[] {
  return (configs ?? []).map((config) => ({
    key: config.key ?? "",
    value: config.value ?? "",
    secret: config.secret ?? false,
    mountPath: config.mounted ? (config.mountPath?.trim() ?? "") : "",
    group: config.group?.trim() ?? "",
    comment: config.comment?.trim() ?? "",
  }))
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
  // Tracks which format fed the confirm dialog. YAML is lossless (carries mount/group/comment/order),
  // so its confirm path applies those fields even when empty; dotenv keeps its non-destructive behavior.
  const [importSource, setImportSource] = useState<"dotenv" | "yaml">("dotenv")
  const [yamlImportEntries, setYamlImportEntries] = useState<
    { key: string; value: string; mounted?: boolean; mountPath?: string | null; group?: string | null; comment?: string | null }[]
  >([])
  const yamlFileInputRef = useRef<HTMLInputElement>(null)

  // Group tab state: the active group tab per section (keyed "config"/"secret"), plus a tick that forces
  // regrouping after a group input blurs so a row does not jump tabs (and lose focus) mid-typing.
  const [searchTerms, setSearchTerms] = useState<Record<string, string>>({})
  const [activeGroups, setActiveGroups] = useState<Record<string, string>>({})
  const [, setGroupingTick] = useState(0)

  const form = useForm<ApplicationConfigFormValues>({
    resolver: zodResolver(applicationConfigSchema),
    defaultValues: {
      configMaps: [],
    },
  })

  const { fields, append, remove, move } = useFieldArray({
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
      await updateApplicationConfigMaps(namespace, applicationName, activeTab, toApiConfigMaps(data.configMaps))
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
          form.reset({ configMaps: toFormConfigMaps(res.data) })
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
    toAdd: { key: string; value: string; mounted?: boolean; mountPath?: string | null; group?: string | null; comment?: string | null }[]
    toReplace: {
      old: { key: string; value: string }
      new: { key: string; value: string; mounted?: boolean; mountPath?: string | null; group?: string | null; comment?: string | null }
    }[]
  }) => {
    const isYaml = importSource === "yaml"
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
        newConfigs.push({
          key: item.key,
          value: item.value,
          secret: false,
          mounted: item.mounted ?? false,
          mountPath: item.mountPath ?? "",
          group: item.group ?? "",
          comment: item.comment ?? "",
        })
      }
    }

    for (const item of result.toReplace) {
      const index = indexByKey.get(item.old.key)
      if (index !== undefined) {
        // YAML is lossless: it always carries mount/group/comment, so overwrite them (empty clears). A plain
        // dotenv import only overwrites group/comment when supplied, so it never wipes editor-set metadata,
        // and it leaves mount settings untouched (dotenv cannot express them).
        newConfigs[index] = {
          ...newConfigs[index],
          key: item.new.key,
          value: item.new.value,
          ...(isYaml
            ? {
                mounted: item.new.mounted ?? false,
                mountPath: item.new.mountPath ?? "",
                group: item.new.group ?? "",
                comment: item.new.comment ?? "",
              }
            : {
                ...(item.new.group ? { group: item.new.group } : {}),
                ...(item.new.comment ? { comment: item.new.comment } : {}),
              }),
        }
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
    setImportSource("dotenv")
    setImportInputDialogOpen(false)
    setImportConfirmDialogOpen(true)
  }

  // Opens the OS file picker for a YAML import. Reset via ref click so the same file can be re-selected.
  const handleImportYamlClick = () => {
    yamlFileInputRef.current?.click()
  }

  const handleYamlFileSelected = async (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0]
    // Clear so selecting the same file again still fires onChange.
    event.target.value = ""
    if (!file) {
      return
    }
    try {
      const text = await file.text()
      const entries = parseYamlConfig(text)
      if (entries.length === 0) {
        toast.error(t("apps.config.importNoValid"))
        return
      }
      setYamlImportEntries(entries)
      setImportSource("yaml")
      setImportMode("key-value")
      setImportConfirmDialogOpen(true)
    } catch {
      toast.error(t("apps.config.yamlParseError"))
    }
  }

  // Downloads all non-secret config items as an OOPS YAML file. Secrets are excluded (matching the dotenv
  // export) and list order preserves the manual arrangement.
  const handleExportYaml = () => {
    const configs = form.getValues("configMaps").filter((c) => !c.secret)
    if (configs.length === 0) {
      toast.error(t("apps.config.exportNoData"))
      return
    }
    const content = serializeYamlConfig(configs, {
      application: applicationName,
      environment: activeTab || undefined,
    })
    const blob = new Blob([content], { type: "application/yaml;charset=utf-8" })
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement("a")
    anchor.href = url
    const envSuffix = activeTab ? `-${activeTab}` : ""
    anchor.download = `${applicationName}${envSuffix}-config.yaml`
    document.body.appendChild(anchor)
    anchor.click()
    document.body.removeChild(anchor)
    URL.revokeObjectURL(url)
    toast.success(t("apps.config.exportSuccess"))
  }

  const handleExportAll = async () => {
    // Export ConfigMap (non-secret) entries only; secrets are never copied to the clipboard. Items are
    // grouped so a "## group:" marker precedes each group and a "#" comment precedes the item it describes,
    // matching what parseEnvContent reads back on import.
    const configs = form.getValues("configMaps").filter((c) => !c.secret)
    const byGroup = new Map<string, typeof configs>()
    for (const config of configs) {
      const group = config.group?.trim() ?? ""
      const bucket = byGroup.get(group)
      if (bucket) {
        bucket.push(config)
      } else {
        byGroup.set(group, [config])
      }
    }
    const groupNames = Array.from(byGroup.keys()).sort((left, right) => {
      if (left === "") return 1
      if (right === "") return -1
      return left.localeCompare(right)
    })

    const formatValue = (value: string) =>
      value.includes(" ") || value.includes("\n") || value.includes('"')
        ? `"${value.replace(/"/g, '\\"')}"`
        : value

    const blocks = groupNames.map((groupName) => {
      const lines: string[] = []
      if (groupName !== "") {
        lines.push(`## group: ${groupName}`)
      }
      for (const config of byGroup.get(groupName) ?? []) {
        const comment = config.comment?.trim()
        if (comment) {
          lines.push(`# ${comment}`)
        }
        lines.push(`${config.key}=${formatValue(config.value)}`)
      }
      return lines.join("\n")
    })
    const envContent = blocks.join("\n\n")

    try {
      await navigator.clipboard.writeText(envContent)
      toast.success(t("apps.config.copySuccess"))
    } catch {
      toast.error(t("apps.config.copyError"))
    }
  }

  const parsedImportContent = parseEnvContent(importContent)
  // The confirm dialog is shared by both import formats; feed it whichever source last triggered it.
  const confirmDialogEntries = importSource === "yaml" ? yamlImportEntries : parsedImportContent

  // Distinct non-empty group names across all items, feeding the <datalist> autocomplete.
  const knownGroups = Array.from(
    new Set(
      (form.getValues("configMaps") ?? [])
        .map((config) => config.group?.trim() ?? "")
        .filter((group) => group !== "")
    )
  ).sort()

  const sensors = useSensors(
    // A small activation distance so a click on the drag handle doesn't get swallowed as a drag.
    useSensor(PointerSensor, { activationConstraint: { distance: 4 } })
  )

  // Assigns/changes an item's group, then forces regrouping so it jumps to the target section.
  const handleSetGroup = (index: number, group: string) => {
    form.setValue(`configMaps.${index}.group`, group, { shouldDirty: true })
    setGroupingTick((tick) => tick + 1)
  }

  // Reorders the underlying field array when a row is dropped. dnd ids are the field ids; we translate them
  // to absolute indices and use useFieldArray.move so each field id travels with its own values (including its
  // group) — keeping dnd-kit's sortable ids in sync with row data (a whole-array setValue would desync them).
  const handleDragEnd = (event: DragEndEvent) => {
    const { active, over } = event
    if (!over || active.id === over.id) return
    const fromIndex = fields.findIndex((field) => field.id === active.id)
    const toIndex = fields.findIndex((field) => field.id === over.id)
    if (fromIndex === -1 || toIndex === -1) return

    // Dragging only reorders — it never changes an item's group. Group is set explicitly via the group dialog.
    move(fromIndex, toIndex)
    setGroupingTick((tick) => tick + 1)
  }

  // A group's rows, rendered as a sortable list of grid rows. No column header: the key/value placeholders
  // label empty rows, and the left-key / right-value layout is self-evident once filled.
  const renderRowsTable = (
    rows: { field: (typeof fields)[number]; index: number }[],
    isSecret: boolean
  ) => (
    <div className="flex flex-col gap-1.5">
      <SortableContext items={rows.map(({ field }) => field.id)} strategy={verticalListSortingStrategy}>
        {rows.map(({ field, index }) => (
          <SortableConfigRow
            key={field.id}
            id={field.id}
            index={index}
            form={form}
            isSecret={isSecret}
            knownGroups={knownGroups}
            onRemove={remove}
            onSetGroup={handleSetGroup}
            t={t}
          />
        ))}
      </SortableContext>
    </div>
  )

  // Per-section filter (key/comment) shown at the right of each section header. Each section keeps its own
  // term, so ConfigMap and Secret filter independently.
  const renderSectionFilter = (isSecret: boolean) => {
    const sectionKey = isSecret ? "secret" : "config"
    return (
      <div className="relative w-56 shrink-0">
        <Search className="absolute left-2.5 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
        <Input
          autoComplete="off"
          placeholder={t("apps.config.searchPlaceholder")}
          className="h-8 pl-8"
          value={searchTerms[sectionKey] ?? ""}
          onChange={(event) =>
            setSearchTerms((previous) => ({ ...previous, [sectionKey]: event.target.value }))
          }
        />
      </div>
    )
  }

  // Groups rows by their (trimmed) group name and exposes each group as a horizontal tab, showing only the
  // active group's rows at a time. Grouping reads live form values so it stays correct after imports, group
  // changes, and drags (re-rendered via groupingTick). A non-empty search forces the flat "all" view so
  // every key/comment match across groups is visible. The section is wrapped in one DndContext so a row can
  // still be dragged onto another group's row while the "all" tab is active.
  const renderSection = (
    rows: { field: (typeof fields)[number]; index: number }[],
    isSecret: boolean
  ) => {
    const sectionKey = isSecret ? "secret" : "config"
    const normalizedSearch = (searchTerms[sectionKey] ?? "").trim().toLowerCase()
    const visibleRows = normalizedSearch
      ? rows.filter(({ index }) => {
          const key = form.getValues(`configMaps.${index}.key`) ?? ""
          const comment = form.getValues(`configMaps.${index}.comment`) ?? ""
          return (
            key.toLowerCase().includes(normalizedSearch) ||
            comment.toLowerCase().includes(normalizedSearch)
          )
        })
      : rows

    const grouped = new Map<string, typeof rows>()
    for (const row of visibleRows) {
      const group = (form.getValues(`configMaps.${row.index}.group`) ?? "").trim()
      const bucket = grouped.get(group)
      if (bucket) {
        bucket.push(row)
      } else {
        grouped.set(group, [row])
      }
    }
    // Named groups sorted alphabetically, ungrouped ("") last.
    const groupNames = Array.from(grouped.keys()).sort((left, right) => {
      if (left === "") return 1
      if (right === "") return -1
      return left.localeCompare(right)
    })
    const hasNamedGroups = groupNames.some((name) => name !== "")

    const storedGroup = activeGroups[sectionKey] ?? ALL_GROUPS
    // Fall back to "all" if the stored tab no longer exists (its group was renamed or emptied). Searching
    // also forces "all" so matches from every group show together.
    const activeGroup =
      normalizedSearch || (storedGroup !== ALL_GROUPS && !groupNames.includes(storedGroup))
        ? ALL_GROUPS
        : storedGroup
    const shownRows = activeGroup === ALL_GROUPS ? visibleRows : grouped.get(activeGroup) ?? []
    // New rows adopt the active group so they land in the tab you are looking at.
    const newRowGroup = activeGroup === ALL_GROUPS ? "" : activeGroup

    const tabs = [ALL_GROUPS, ...groupNames]

    return (
      <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={handleDragEnd}>
        <div className="flex flex-col gap-3">
          {hasNamedGroups && !normalizedSearch && (
            <div className="flex flex-wrap items-center gap-x-1 border-b">
              {tabs.map((name) => {
                const count = name === ALL_GROUPS ? visibleRows.length : grouped.get(name)?.length ?? 0
                const label =
                  name === ALL_GROUPS
                    ? t("apps.config.allGroups")
                    : name === ""
                      ? t("apps.config.ungrouped")
                      : name
                const isActive = activeGroup === name
                return (
                  <button
                    key={name === "" ? "__ungrouped__" : name}
                    type="button"
                    onClick={() => setActiveGroups((previous) => ({ ...previous, [sectionKey]: name }))}
                    className={`-mb-px flex items-center gap-1.5 border-b-2 px-3 py-1.5 text-sm cursor-pointer transition-colors ${
                      isActive
                        ? "border-primary font-medium text-foreground"
                        : "border-transparent text-muted-foreground hover:text-foreground"
                    }`}
                  >
                    <span>{label}</span>
                    <span className="text-xs text-muted-foreground">{count}</span>
                  </button>
                )
              })}
            </div>
          )}
          {shownRows.length === 0 ? (
            <div className="rounded-md border h-12 flex items-center justify-center text-sm text-muted-foreground">
              {isLoadingConfig
                ? t("common.loading")
                : normalizedSearch
                  ? t("apps.config.noSearchResult")
                  : t("apps.config.noConfig")}
            </div>
          ) : (
            renderRowsTable(shownRows, isSecret)
          )}
          <Button
            type="button"
            variant="outline"
            className="w-full justify-center cursor-pointer"
            onClick={() => append({ key: "", value: "", secret: isSecret, mounted: false, mountPath: "", group: newRowGroup, comment: "" })}
            disabled={isLoadingConfig}
          >
            <Plus className="size-4" />
            {t("apps.config.addItem")}
          </Button>
        </div>
      </DndContext>
    )
  }

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
            />

              {/* ConfigMap section */}
              <div className="flex flex-col gap-3">
                <div className="flex items-center justify-between gap-2">
                  <div className="flex items-center gap-1.5">
                    <Container className="size-4 text-muted-foreground" />
                    <span className="text-sm font-semibold">{t("apps.config.configMapSection")}</span>
                    <Tooltip>
                      <TooltipTrigger asChild>
                        <span className="text-muted-foreground cursor-help">
                          <Info className="size-3.5" />
                        </span>
                      </TooltipTrigger>
                      <TooltipContent className="max-w-xs">{t("apps.config.configMapHint")}</TooltipContent>
                    </Tooltip>
                  </div>
                  {renderSectionFilter(false)}
                </div>
                {renderSection(configMapFields, false)}

                <div className="flex gap-2">
                  <DropdownMenu>
                    <DropdownMenuTrigger asChild>
                      <Button
                        type="button"
                        variant="outline"
                        className="cursor-pointer"
                        disabled={isLoadingConfig}
                      >
                        <Download className="size-4" />
                        {t("apps.config.export")}
                        <ChevronDown className="size-4" />
                      </Button>
                    </DropdownMenuTrigger>
                    <DropdownMenuContent align="start">
                      <DropdownMenuItem className="cursor-pointer" onClick={handleExportAll}>
                        <Copy className="size-4" />
                        {t("apps.config.copyAll")}
                      </DropdownMenuItem>
                      <DropdownMenuItem className="cursor-pointer" onClick={handleExportYaml}>
                        <FileDown className="size-4" />
                        {t("apps.config.exportYaml")}
                      </DropdownMenuItem>
                    </DropdownMenuContent>
                  </DropdownMenu>

                  <DropdownMenu>
                    <DropdownMenuTrigger asChild>
                      <Button
                        type="button"
                        variant="outline"
                        className="cursor-pointer"
                        disabled={isLoadingConfig}
                      >
                        <Upload className="size-4" />
                        {t("apps.config.import")}
                        <ChevronDown className="size-4" />
                      </Button>
                    </DropdownMenuTrigger>
                    <DropdownMenuContent align="start">
                      <DropdownMenuItem
                        className="cursor-pointer"
                        onClick={() => {
                          setImportSource("dotenv")
                          setImportInputDialogOpen(true)
                        }}
                      >
                        <Upload className="size-4" />
                        {t("apps.config.importDotenv")}
                      </DropdownMenuItem>
                      <DropdownMenuItem className="cursor-pointer" onClick={handleImportYamlClick}>
                        <FileUp className="size-4" />
                        {t("apps.config.importYaml")}
                      </DropdownMenuItem>
                    </DropdownMenuContent>
                  </DropdownMenu>

                  <input
                    ref={yamlFileInputRef}
                    type="file"
                    accept=".yaml,.yml"
                    className="hidden"
                    onChange={handleYamlFileSelected}
                  />

                  <Dialog open={importInputDialogOpen} onOpenChange={setImportInputDialogOpen}>
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

              <div className="border-t" />

              {/* Secret section */}
              <div className="flex flex-col gap-3">
                <div className="flex items-center justify-between gap-2">
                  <div className="flex items-center gap-1.5">
                    <KeyRound className="size-4 text-muted-foreground" />
                    <span className="text-sm font-semibold">{t("apps.config.secretSection")}</span>
                    <Tooltip>
                      <TooltipTrigger asChild>
                        <span className="text-muted-foreground cursor-help">
                          <Info className="size-3.5" />
                        </span>
                      </TooltipTrigger>
                      <TooltipContent className="max-w-xs">{t("apps.config.secretHint")}</TooltipContent>
                    </Tooltip>
                  </div>
                  {renderSectionFilter(true)}
                </div>
                {renderSection(secretFields, true)}
              </div>
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
      parsedEnvContent={confirmDialogEntries}
      importMode={importMode}
      compareMetadata={importSource === "yaml"}
      onConfirm={handleImportConfirm}
    />
    </>
  )
})
