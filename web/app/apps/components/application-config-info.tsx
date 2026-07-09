"use client"

import { forwardRef, useCallback, useEffect, useLayoutEffect, useRef, useState } from "react"
import { useForm, useFieldArray, type UseFormReturn } from "react-hook-form"
import { zodResolver } from "@hookform/resolvers/zod"
import { Plus, Trash2, Loader2, Upload, Copy, Container, KeyRound, ChevronDown, ChevronRight, Search, GripVertical, Tags } from "lucide-react"
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
  arrayMove,
} from "@dnd-kit/sortable"
import { CSS } from "@dnd-kit/utilities"
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover"

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
  DialogTrigger,
} from "@/components/ui/dialog"
import { Textarea } from "@/components/ui/textarea"
import { Label } from "@/components/ui/label"
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group"
import { Checkbox } from "@/components/ui/checkbox"
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
const CONFIG_GRID = "grid grid-cols-[24px_minmax(0,1.1fr)_minmax(0,1.2fr)_minmax(0,1fr)_minmax(0,1fr)_auto] items-start gap-3"

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
  const [groupOpen, setGroupOpen] = useState(false)
  const [newGroup, setNewGroup] = useState("")

  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
    zIndex: isDragging ? 10 : undefined,
  }

  const currentGroup = form.watch(`configMaps.${index}.group`)?.trim() ?? ""
  const isMounted = form.watch(`configMaps.${index}.mounted`)

  const applyGroup = (group: string) => {
    onSetGroup(index, group)
    setGroupOpen(false)
    setNewGroup("")
  }

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
                  <Input autoComplete="off" placeholder="key" {...field} />
                </FormControl>
                {showEnvNameWarning && (
                  <p className="mt-1 text-xs text-amber-600">{t("apps.config.envNameWarning")}</p>
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

      {/* Mount path */}
      <div className="flex items-center gap-2">
        <FormField
          control={form.control}
          name={`configMaps.${index}.mounted`}
          render={({ field }) => (
            <Checkbox
              id={`mount-${index}`}
              className="mt-1.5 cursor-pointer"
              checked={!!field.value}
              onCheckedChange={(checked) => {
                field.onChange(checked === true)
                if (checked !== true) {
                  form.setValue(`configMaps.${index}.mountPath`, "", { shouldDirty: true })
                }
              }}
            />
          )}
        />
        {isMounted ? (
          <FormField
            control={form.control}
            name={`configMaps.${index}.mountPath`}
            render={({ field }) => (
              <FormItem className="flex-1">
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
        ) : (
          <Label htmlFor={`mount-${index}`} className="mt-1 text-xs text-muted-foreground cursor-pointer">
            {t("apps.config.mountAsFile")}
          </Label>
        )}
      </div>

      {/* Comment (moved here from under the Key; keeps rows single-height and aligned) */}
      <div>
        <FormField
          control={form.control}
          name={`configMaps.${index}.comment`}
          render={({ field }) => (
            <Input
              autoComplete="off"
              placeholder={t("apps.config.commentPlaceholder")}
              className="text-xs"
              {...field}
              value={field.value ?? ""}
            />
          )}
        />
      </div>

      {/* Actions: group popover + delete */}
      <div className="flex items-center gap-0.5">
        <Popover open={groupOpen} onOpenChange={setGroupOpen}>
          <PopoverTrigger asChild>
            <Button
              type="button"
              variant="ghost"
              size="icon"
              className={`cursor-pointer ${currentGroup ? "text-primary" : ""}`}
              title={currentGroup || t("apps.config.setGroup")}
            >
              <Tags className="size-4" />
            </Button>
          </PopoverTrigger>
          <PopoverContent align="end" className="w-56 p-2">
            <div className="flex flex-col gap-1">
              <p className="px-1 pb-1 text-xs font-medium text-muted-foreground">{t("apps.config.setGroup")}</p>
              <button
                type="button"
                className={`flex items-center justify-between rounded px-2 py-1.5 text-sm hover:bg-accent cursor-pointer ${currentGroup === "" ? "font-medium" : ""}`}
                onClick={() => applyGroup("")}
              >
                {t("apps.config.ungrouped")}
                {currentGroup === "" && <span className="text-xs text-primary">✓</span>}
              </button>
              {knownGroups.map((group) => (
                <button
                  key={group}
                  type="button"
                  className={`flex items-center justify-between rounded px-2 py-1.5 text-sm hover:bg-accent cursor-pointer ${currentGroup === group ? "font-medium" : ""}`}
                  onClick={() => applyGroup(group)}
                >
                  {group}
                  {currentGroup === group && <span className="text-xs text-primary">✓</span>}
                </button>
              ))}
              <form
                className="mt-1 flex gap-1 border-t pt-2"
                onSubmit={(event) => {
                  event.preventDefault()
                  const trimmed = newGroup.trim()
                  if (trimmed) applyGroup(trimmed)
                }}
              >
                <Input
                  autoComplete="off"
                  placeholder={t("apps.config.newGroupPlaceholder")}
                  className="h-8 text-xs"
                  value={newGroup}
                  onChange={(event) => setNewGroup(event.target.value)}
                />
                <Button type="submit" size="sm" className="h-8 cursor-pointer" disabled={!newGroup.trim()}>
                  {t("apps.config.addGroup")}
                </Button>
              </form>
            </div>
          </PopoverContent>
        </Popover>
        <Button
          type="button"
          variant="ghost"
          size="icon"
          className="cursor-pointer"
          onClick={() => onRemove(index)}
        >
          <Trash2 className="size-4 text-destructive" />
        </Button>
      </div>
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

  // Group sections state: collapsed group keys plus a tick that forces regrouping after a group input
  // blurs. Regrouping only on blur keeps a row from jumping to another table (and losing focus) while
  // its group name is being typed.
  const [searchTerm, setSearchTerm] = useState("")
  const [collapsedGroups, setCollapsedGroups] = useState<Set<string>>(new Set())
  const [, setGroupingTick] = useState(0)

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
    toAdd: { key: string; value: string; group?: string | null; comment?: string | null }[]
    toReplace: {
      old: { key: string; value: string }
      new: { key: string; value: string; group?: string | null; comment?: string | null }
    }[]
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
        newConfigs.push({
          key: item.key,
          value: item.value,
          secret: false,
          mounted: false,
          mountPath: "",
          group: item.group ?? "",
          comment: item.comment ?? "",
        })
      }
    }

    for (const item of result.toReplace) {
      const index = indexByKey.get(item.old.key)
      if (index !== undefined) {
        // Only overwrite group/comment when the import actually supplies one, so a plain KEY=value
        // import never wipes metadata the user set in the editor.
        newConfigs[index] = {
          ...newConfigs[index],
          key: item.new.key,
          value: item.new.value,
          ...(item.new.group ? { group: item.new.group } : {}),
          ...(item.new.comment ? { comment: item.new.comment } : {}),
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
    setImportInputDialogOpen(false)
    setImportConfirmDialogOpen(true)
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

  // Distinct non-empty group names across all items, feeding the <datalist> autocomplete.
  const knownGroups = Array.from(
    new Set(
      (form.getValues("configMaps") ?? [])
        .map((config) => config.group?.trim() ?? "")
        .filter((group) => group !== "")
    )
  ).sort()

  const toggleGroup = (groupKey: string) => {
    setCollapsedGroups((previous) => {
      const next = new Set(previous)
      if (next.has(groupKey)) {
        next.delete(groupKey)
      } else {
        next.add(groupKey)
      }
      return next
    })
  }

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
  // to absolute indices, move within the full array, and write the array back so order persists on save.
  const handleDragEnd = (event: DragEndEvent) => {
    const { active, over } = event
    if (!over || active.id === over.id) return
    const configs = form.getValues("configMaps") ?? []
    const fromIndex = fields.findIndex((field) => field.id === active.id)
    const toIndex = fields.findIndex((field) => field.id === over.id)
    if (fromIndex === -1 || toIndex === -1) return

    // Dropping onto a row in another group also adopts that row's group (cross-group move).
    const targetGroup = configs[toIndex]?.group?.trim() ?? ""
    const moved = arrayMove(configs, fromIndex, toIndex).map((config, position) =>
      position === toIndex ? { ...config, group: targetGroup } : config
    )
    form.setValue("configMaps", moved, { shouldDirty: true })
    setGroupingTick((tick) => tick + 1)
  }

  // A group's rows, rendered as a sortable list of grid rows preceded by the shared column header.
  const renderRowsTable = (
    rows: { field: (typeof fields)[number]; index: number }[],
    isSecret: boolean
  ) => (
    <div className="flex flex-col gap-1.5">
      <div className={`${CONFIG_GRID} px-2 text-sm font-medium text-muted-foreground`}>
        <span />
        <span>Key</span>
        <span>Value</span>
        <span>{t("apps.config.mountPath")}</span>
        <span>{t("apps.config.comment")}</span>
        <span />
      </div>
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

  // Groups rows by their (trimmed) group name into collapsible sections. Grouping reads live form values,
  // so it stays correct after imports, group changes, and drags (re-rendered via groupingTick). A non-empty
  // search term filters rows by key/comment and overrides collapse so every match is visible. The whole
  // section is wrapped in one DndContext so a row can be dragged from one group onto another.
  const renderSection = (
    rows: { field: (typeof fields)[number]; index: number }[],
    isSecret: boolean
  ) => {
    const normalizedSearch = searchTerm.trim().toLowerCase()
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

    return (
      <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={handleDragEnd}>
        <div className="flex flex-col gap-3">
          {visibleRows.length === 0 && (
            <div className="rounded-md border h-12 flex items-center justify-center text-sm text-muted-foreground">
              {isLoadingConfig
                ? t("common.loading")
                : normalizedSearch
                  ? t("apps.config.noSearchResult")
                  : t("apps.config.noConfig")}
            </div>
          )}
          {groupNames.map((groupName) => {
            const groupRows = grouped.get(groupName) ?? []
            const groupKey = `${isSecret ? "secret" : "config"}:${groupName}`
            const collapsed = !normalizedSearch && collapsedGroups.has(groupKey)
            // Without any named group, skip the header chrome entirely and render the flat list.
            if (!hasNamedGroups) {
              return <div key={groupKey}>{renderRowsTable(groupRows, isSecret)}</div>
            }
            return (
              <div key={groupKey} className="flex flex-col gap-1.5">
                <button
                  type="button"
                  className="flex items-center gap-1.5 py-1 text-sm font-semibold text-foreground hover:text-primary cursor-pointer w-fit"
                  onClick={() => toggleGroup(groupKey)}
                >
                  {collapsed ? <ChevronRight className="size-4" /> : <ChevronDown className="size-4" />}
                  <span>{groupName === "" ? t("apps.config.ungrouped") : groupName}</span>
                  <span className="text-xs font-normal text-muted-foreground">({groupRows.length})</span>
                </button>
                {!collapsed && renderRowsTable(groupRows, isSecret)}
              </div>
            )
          })}
          <Button
            type="button"
            variant="outline"
            className="w-full justify-center cursor-pointer"
            onClick={() => append({ key: "", value: "", secret: isSecret, mounted: false, mountPath: "", group: "", comment: "" })}
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
            >
              {/* Key/comment search across both sections. */}
              <div className="relative w-full max-w-sm">
                <Search className="absolute left-2.5 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
                <Input
                  autoComplete="off"
                  placeholder={t("apps.config.searchPlaceholder")}
                  className="pl-8"
                  value={searchTerm}
                  onChange={(event) => setSearchTerm(event.target.value)}
                />
              </div>

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
