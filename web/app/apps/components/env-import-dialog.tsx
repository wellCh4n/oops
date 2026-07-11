"use client"

import { useState, useMemo } from "react"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import { Label } from "@/components/ui/label"
import { useLanguage } from "@/contexts/language-context"

interface ConfigMapEntry {
  key: string
  value: string
  mounted?: boolean
  mountPath?: string | null
  group?: string | null
  comment?: string | null
}

interface EnvImportDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  currentConfigs: ConfigMapEntry[]
  parsedEnvContent: ConfigMapEntry[]
  importMode: "key-only" | "key-value"
  // When true (YAML import), a key counts as changed if its value OR its group/comment/mount path differs,
  // so lossless metadata edits are applied. Off (dotenv) keeps the value-only comparison.
  compareMetadata?: boolean
  onConfirm: (result: {
    toAdd: ConfigMapEntry[]
    toReplace: { old: ConfigMapEntry; new: ConfigMapEntry }[]
  }) => void
}

// Trimmed, null-safe view of an entry's metadata used to detect meaningful changes. Mount path only counts
// when the item is actually mounted, matching how the backend derives `mounted` from a non-blank path.
function metadataFingerprint(entry: ConfigMapEntry): string {
  const group = (entry.group ?? "").trim()
  const comment = (entry.comment ?? "").trim()
  const mountPath = entry.mounted ? (entry.mountPath ?? "").trim() : ""
  return JSON.stringify([group, comment, mountPath])
}

export function EnvImportDialog({
  open,
  onOpenChange,
  currentConfigs,
  parsedEnvContent,
  importMode,
  compareMetadata = false,
  onConfirm,
}: EnvImportDialogProps) {
  const [selectedReplaces, setSelectedReplaces] = useState<Set<string>>(new Set())
  const { t } = useLanguage()

  // 分析哪些需要添加，哪些需要替换
  const { toAdd, toReplace, noConflict } = useMemo(() => {
    const currentByKey = new Map(currentConfigs.map(c => [c.key, c]))
    const toAdd: ConfigMapEntry[] = []
    const toReplace: { old: ConfigMapEntry; new: ConfigMapEntry; hasConflict: boolean }[] = []
    const noConflict: ConfigMapEntry[] = []

    for (const entry of parsedEnvContent) {
      const existing = currentByKey.get(entry.key)
      if (existing === undefined) {
        // Key 不存在，需要添加
        toAdd.push(entry)
      } else if (
        importMode === "key-value" &&
        (existing.value !== entry.value ||
          (compareMetadata && metadataFingerprint(existing) !== metadataFingerprint(entry)))
      ) {
        // Key 存在但值或元信息不同，需要替换
        toReplace.push({
          old: { key: entry.key, value: existing.value },
          new: entry,
          hasConflict: true,
        })
      } else {
        // Key 存在且值/元信息相同（或只导入 key），跳过
        noConflict.push(entry)
      }
    }

    return { toAdd, toReplace, noConflict }
  }, [currentConfigs, parsedEnvContent, importMode, compareMetadata])

  const handleConfirm = () => {
    const selectedReplacesList: { old: ConfigMapEntry; new: ConfigMapEntry }[] = []
    const allToAdd: ConfigMapEntry[] = [...toAdd]
    for (const r of toReplace) {
      if (selectedReplaces.has(r.new.key)) {
        selectedReplacesList.push({ old: r.old, new: r.new })
      } else {
        allToAdd.push(r.new)
      }
    }

    onConfirm({
      toAdd: allToAdd,
      toReplace: selectedReplacesList,
    })
    setSelectedReplaces(new Set())
    onOpenChange(false)
  }

  const handleCancel = () => {
    setSelectedReplaces(new Set())
    onOpenChange(false)
  }

  const toggleReplace = (key: string) => {
    setSelectedReplaces(prev => {
      const next = new Set(prev)
      if (next.has(key)) {
        next.delete(key)
      } else {
        next.add(key)
      }
      return next
    })
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-2xl max-h-[90vh] flex flex-col overflow-hidden">
        <DialogHeader>
          <DialogTitle>{t("apps.config.importTitle")}</DialogTitle>
          <DialogDescription>
            {importMode === "key-only"
              ? t("apps.config.importKeyOnlyDesc")
              : t("apps.config.importKeyValueDesc")}
          </DialogDescription>
        </DialogHeader>

        <div className="flex-1 min-h-0 overflow-y-auto pr-4 pb-4">
          <div className="space-y-6">
            {/* 需要添加的新配置 */}
            {toAdd.length > 0 && (
              <div className="space-y-2">
                <Label className="text-sm font-medium text-green-600">
                  {t("apps.config.newEntries")} ({toAdd.length})
                </Label>
                <div className="rounded-md border bg-green-50 dark:bg-green-950/20 p-3 space-y-2">
                  {toAdd.map(entry => (
                    <div key={entry.key} className="flex items-center gap-2 text-sm">
                      <span className="font-mono text-green-700 dark:text-green-400">{entry.key}</span>
                      {importMode === "key-value" && (
                        <>
                          <span className="text-muted-foreground">=</span>
                          <span className="font-mono text-green-600 dark:text-green-500 truncate">{entry.value}</span>
                        </>
                      )}
                      {entry.group && (
                        <span className="rounded bg-green-100 dark:bg-green-900/40 px-1.5 py-0.5 text-xs text-green-700 dark:text-green-400">
                          {entry.group}
                        </span>
                      )}
                    </div>
                  ))}
                </div>
              </div>
            )}

            {/* 需要替换的配置（仅 key-value 模式） */}
            {importMode === "key-value" && toReplace.length > 0 && (
              <div className="space-y-2">
                <Label className="text-sm font-medium text-amber-600">
                  {t("apps.config.valueConflict")} ({toReplace.length})
                </Label>
                <div className="rounded-md border border-amber-200 bg-amber-50 dark:bg-amber-950/20 dark:border-amber-900 p-3 space-y-3">
                  {toReplace.map(entry => (
                    <div key={entry.new.key} className="space-y-1">
                      <div className="flex items-center gap-2">
                        <Checkbox
                          id={`replace-${entry.new.key}`}
                          checked={selectedReplaces.has(entry.new.key)}
                          onCheckedChange={() => toggleReplace(entry.new.key)}
                        />
                        <Label
                          htmlFor={`replace-${entry.new.key}`}
                          className="font-mono text-sm cursor-pointer"
                        >
                          {entry.new.key}
                        </Label>
                      </div>
                      <div className="ml-6 space-y-1 text-xs">
                        {entry.old.value !== entry.new.value ? (
                          <>
                            <div className="flex items-center gap-2">
                              <span className="text-muted-foreground">{t("apps.config.currentValue")}：</span>
                              <span className="font-mono text-red-600 dark:text-red-400 line-through">
                                {entry.old.value || `(${t("common.empty")})`}
                              </span>
                            </div>
                            <div className="flex items-center gap-2">
                              <span className="text-muted-foreground">{t("apps.config.newValue")}：</span>
                              <span className="font-mono text-green-600 dark:text-green-400">
                                {entry.new.value || `(${t("common.empty")})`}
                              </span>
                            </div>
                          </>
                        ) : (
                          <div className="text-muted-foreground">{t("apps.config.metadataOnlyChange")}</div>
                        )}
                        {(entry.new.group?.trim() ||
                          entry.new.comment?.trim() ||
                          (entry.new.mounted && entry.new.mountPath?.trim())) && (
                          <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-muted-foreground">
                            {entry.new.group?.trim() && (
                              <span>
                                {t("apps.config.group")}:{" "}
                                <span className="text-foreground">{entry.new.group.trim()}</span>
                              </span>
                            )}
                            {entry.new.mounted && entry.new.mountPath?.trim() && (
                              <span>
                                {t("apps.config.mountPath")}:{" "}
                                <span className="font-mono text-foreground">{entry.new.mountPath.trim()}</span>
                              </span>
                            )}
                            {entry.new.comment?.trim() && (
                              <span>
                                {t("apps.config.comment")}:{" "}
                                <span className="text-foreground">{entry.new.comment.trim()}</span>
                              </span>
                            )}
                          </div>
                        )}
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            )}

            {/* 无冲突的配置 */}
            {noConflict.length > 0 && (
              <div className="space-y-2">
                <Label className="text-sm font-medium text-muted-foreground">
                  {t("apps.config.skip")} ({noConflict.length}) - {t("apps.config.sameValueSkip")}
                </Label>
                <div className="rounded-md border bg-muted/50 p-3">
                  <div className="flex flex-wrap gap-2">
                    {noConflict.map(entry => (
                      <span
                        key={entry.key}
                        className="font-mono text-xs px-2 py-1 bg-muted rounded text-muted-foreground"
                      >
                        {entry.key}
                      </span>
                    ))}
                  </div>
                </div>
              </div>
            )}

            {/* 空状态 */}
            {parsedEnvContent.length === 0 && (
              <div className="text-center py-8 text-muted-foreground">
                {t("apps.config.importNoValid")}
              </div>
            )}
          </div>
        </div>

        <DialogFooter className="mt-auto shrink-0">
          <Button variant="outline" onClick={handleCancel}>
            {t("common.cancel")}
          </Button>
          <Button onClick={handleConfirm}>
            {t("apps.config.confirmImport")} ({toAdd.length + selectedReplaces.size})
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

// 解析 .env 格式内容。扩展了两类注释标记：
//   ## group: xxx  —— 切换当前分组，作用于其后的所有条目（空组名重置为未分组）
//   # comment      —— 作为紧随其后的那个条目的注释；被空行或新条目消费后清空
export function parseEnvContent(content: string): ConfigMapEntry[] {
  const lines = content.split(/\r?\n/)
  const entries: ConfigMapEntry[] = []
  let currentGroup = ""
  let pendingComment = ""

  for (const line of lines) {
    const trimmed = line.trim()

    // 空行断开注释与下一个条目的关联
    if (!trimmed) {
      pendingComment = ""
      continue
    }

    if (trimmed.startsWith("#")) {
      const groupMarker = trimmed.match(/^##\s*group\s*:\s*(.*)$/i)
      if (groupMarker) {
        currentGroup = groupMarker[1].trim()
        pendingComment = ""
      } else {
        // 普通注释挂到下一个 key；多行注释合并为一条
        const commentText = trimmed.replace(/^#+\s?/, "")
        pendingComment = pendingComment ? `${pendingComment} ${commentText}` : commentText
      }
      continue
    }

    // 解析 KEY=VALUE 格式
    const equalIndex = trimmed.indexOf("=")
    if (equalIndex === -1) {
      // 没有 =，只导入 key
      if (trimmed.match(/^[A-Za-z_][A-Za-z0-9_]*$/)) {
        entries.push({ key: trimmed, value: "", group: currentGroup, comment: pendingComment })
        pendingComment = ""
      }
      continue
    }

    const key = trimmed.substring(0, equalIndex).trim()
    let value = trimmed.substring(equalIndex + 1).trim()

    // 去除引号
    if ((value.startsWith('"') && value.endsWith('"')) ||
        (value.startsWith("'") && value.endsWith("'"))) {
      value = value.slice(1, -1)
    }

    if (key) {
      entries.push({ key, value, group: currentGroup, comment: pendingComment })
      pendingComment = ""
    }
  }

  return entries
}