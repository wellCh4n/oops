"use client"

import { useCallback, useEffect, useMemo, useRef, useState } from "react"
import dynamic from "next/dynamic"
import {
  ChevronRight,
  File as FileIcon,
  FileSymlink,
  Folder,
  FolderPlus,
  FolderSymlink,
  Loader2,
  RefreshCw,
} from "lucide-react"
import { useTheme } from "next-themes"
import { PodFileEntry } from "@/lib/api/pod-files"
import { cn } from "@/lib/utils"
import { useLanguage } from "@/contexts/language-context"
import { toast } from "sonner"
import {
  ContextMenu,
  ContextMenuContent,
  ContextMenuItem,
  ContextMenuSeparator,
  ContextMenuTrigger,
} from "@/components/ui/context-menu"
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from "@/components/ui/tooltip"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"

const MonacoEditor = dynamic(() => import("@monaco-editor/react"), { ssr: false })

export interface FileTreeProps {
  listDirectory: (path: string) => Promise<PodFileEntry[]>
  getDownloadUrl: (path: string) => string
  uploadFile?: (parentDir: string, file: File) => Promise<void>
  getFileContent?: (path: string) => Promise<string>
  saveFileContent?: (path: string, content: string) => Promise<void>
  deletePath?: (path: string) => Promise<void>
  renamePath?: (fromPath: string, toPath: string) => Promise<void>
  createDirectory?: (path: string) => Promise<void>
  rootPath?: string
}

interface NodeState {
  entries: PodFileEntry[] | null
  loading: boolean
  error: string | null
  expanded: boolean
}

interface EditState {
  path: string
  language: string
  original: string
  draft: string
  loading: boolean
  saving: boolean
  error: string | null
}

interface RenameState {
  entry: PodFileEntry
  draft: string
  saving: boolean
  error: string | null
}

interface DeleteState {
  entry: PodFileEntry
  deleting: boolean
}

interface MkdirState {
  parentDir: string
  draft: string
  saving: boolean
  error: string | null
}

function detectLanguage(name: string): string {
  const lower = name.toLowerCase()
  if (lower === "dockerfile" || lower.endsWith(".dockerfile")) return "dockerfile"
  const dot = lower.lastIndexOf(".")
  if (dot < 0) return "plaintext"
  const ext = lower.slice(dot + 1)
  switch (ext) {
    case "ts":
    case "tsx":
      return "typescript"
    case "js":
    case "jsx":
      return "javascript"
    case "json":
      return "json"
    case "yaml":
    case "yml":
      return "yaml"
    case "sh":
    case "bash":
      return "shell"
    case "py":
      return "python"
    case "java":
      return "java"
    case "go":
      return "go"
    case "rs":
      return "rust"
    case "html":
    case "htm":
      return "html"
    case "css":
      return "css"
    case "scss":
      return "scss"
    case "md":
    case "markdown":
      return "markdown"
    case "xml":
      return "xml"
    case "sql":
      return "sql"
    case "toml":
      return "ini"
    case "ini":
    case "conf":
      return "ini"
    case "properties":
      return "properties"
    default:
      return "plaintext"
  }
}

function pathBaseName(path: string): string {
  const trimmed = path.replace(/\/+$/, "")
  const idx = trimmed.lastIndexOf("/")
  return idx >= 0 ? trimmed.slice(idx + 1) : trimmed
}

function parentDirOf(path: string): string {
  if (!path || path === "/") return "/"
  const trimmed = path.replace(/\/+$/, "")
  const idx = trimmed.lastIndexOf("/")
  if (idx <= 0) return "/"
  return trimmed.slice(0, idx)
}

export default function FileTree({
  listDirectory,
  getDownloadUrl,
  uploadFile,
  getFileContent,
  saveFileContent,
  deletePath,
  renamePath,
  createDirectory,
  rootPath = "/",
}: FileTreeProps) {
  const { t } = useLanguage()
  const { resolvedTheme } = useTheme()
  const [nodes, setNodes] = useState<Record<string, NodeState>>({})
  const [uploadingDirs, setUploadingDirs] = useState<Record<string, boolean>>({})
  const [edit, setEdit] = useState<EditState | null>(null)
  const [rename, setRename] = useState<RenameState | null>(null)
  const [del, setDel] = useState<DeleteState | null>(null)
  const [mkdir, setMkdir] = useState<MkdirState | null>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const fileInputTargetRef = useRef<string | null>(null)

  const loadDirectory = useCallback(
    async (path: string) => {
      setNodes((prev) => ({
        ...prev,
        [path]: {
          ...(prev[path] ?? { entries: null, expanded: true }),
          loading: true,
          error: null,
          expanded: true,
        },
      }))
      try {
        const entries = await listDirectory(path)
        setNodes((prev) => ({
          ...prev,
          [path]: { entries, loading: false, error: null, expanded: true },
        }))
      } catch (err) {
        setNodes((prev) => ({
          ...prev,
          [path]: {
            entries: prev[path]?.entries ?? null,
            loading: false,
            error: err instanceof Error ? err.message : "Failed to load",
            expanded: true,
          },
        }))
      }
    },
    [listDirectory],
  )

  useEffect(() => {
    loadDirectory(rootPath)
  }, [loadDirectory, rootPath])

  const toggle = useCallback(
    (entry: PodFileEntry) => {
      if (entry.type !== "DIRECTORY" && entry.type !== "SYMLINK_DIRECTORY") return
      const existing = nodes[entry.path]
      if (existing?.expanded) {
        setNodes((prev) => ({ ...prev, [entry.path]: { ...existing, expanded: false } }))
      } else if (existing?.entries) {
        setNodes((prev) => ({ ...prev, [entry.path]: { ...existing, expanded: true } }))
      } else {
        loadDirectory(entry.path)
      }
    },
    [loadDirectory, nodes],
  )

  const handleUpload = useCallback(
    async (parentDir: string, file: File) => {
      if (!uploadFile) return
      setUploadingDirs((prev) => ({ ...prev, [parentDir]: true }))
      try {
        await uploadFile(parentDir, file)
        toast.success(t("terminal.files.uploadSuccess"))
        await loadDirectory(parentDir)
      } catch (err) {
        toast.error(err instanceof Error ? err.message : "Upload failed")
      } finally {
        setUploadingDirs((prev) => {
          const next = { ...prev }
          delete next[parentDir]
          return next
        })
      }
    },
    [uploadFile, t, loadDirectory],
  )

  const triggerUpload = useCallback((parentDir: string) => {
    fileInputTargetRef.current = parentDir
    if (fileInputRef.current) {
      fileInputRef.current.value = ""
      fileInputRef.current.click()
    }
  }, [])

  const onFileInputChange = useCallback(
    async (event: React.ChangeEvent<HTMLInputElement>) => {
      const file = event.target.files?.[0]
      const target = fileInputTargetRef.current
      fileInputTargetRef.current = null
      if (!file || !target) return
      await handleUpload(target, file)
    },
    [handleUpload],
  )

  const openEdit = useCallback(
    async (entry: PodFileEntry) => {
      if (!getFileContent) return
      const language = detectLanguage(entry.name)
      setEdit({
        path: entry.path,
        language,
        original: "",
        draft: "",
        loading: true,
        saving: false,
        error: null,
      })
      try {
        const content = await getFileContent(entry.path)
        setEdit({
          path: entry.path,
          language,
          original: content,
          draft: content,
          loading: false,
          saving: false,
          error: null,
        })
      } catch (err) {
        setEdit({
          path: entry.path,
          language,
          original: "",
          draft: "",
          loading: false,
          saving: false,
          error: err instanceof Error ? err.message : "Failed to load file",
        })
      }
    },
    [getFileContent],
  )

  const handleSaveEdit = useCallback(async () => {
    if (!edit || !saveFileContent) return
    setEdit({ ...edit, saving: true, error: null })
    try {
      await saveFileContent(edit.path, edit.draft)
      toast.success(t("terminal.files.saveSuccess"))
      setEdit(null)
    } catch (err) {
      setEdit((prev) =>
        prev ? { ...prev, saving: false, error: err instanceof Error ? err.message : "Save failed" } : prev,
      )
    }
  }, [edit, saveFileContent, t])

  const editorTheme = useMemo(() => (resolvedTheme === "dark" ? "vs-dark" : "vs-light"), [resolvedTheme])

  const refreshParent = useCallback(
    (path: string) => {
      const parent = parentDirOf(path)
      if (nodes[parent]) {
        loadDirectory(parent)
      } else if (parent === rootPath) {
        loadDirectory(rootPath)
      }
    },
    [nodes, loadDirectory, rootPath],
  )

  const openRename = useCallback((entry: PodFileEntry) => {
    setRename({ entry, draft: entry.name, saving: false, error: null })
  }, [])

  const handleRenameConfirm = useCallback(async () => {
    if (!rename || !renamePath) return
    const trimmed = rename.draft.trim()
    if (!trimmed || trimmed === rename.entry.name) {
      setRename(null)
      return
    }
    if (trimmed.includes("/") || trimmed === "." || trimmed === "..") {
      setRename({ ...rename, error: t("terminal.files.renameInvalid") })
      return
    }
    const parent = parentDirOf(rename.entry.path)
    const toPath = parent === "/" ? `/${trimmed}` : `${parent}/${trimmed}`
    setRename({ ...rename, saving: true, error: null })
    try {
      await renamePath(rename.entry.path, toPath)
      toast.success(t("terminal.files.renameSuccess"))
      setRename(null)
      refreshParent(rename.entry.path)
    } catch (err) {
      setRename((prev) =>
        prev ? { ...prev, saving: false, error: err instanceof Error ? err.message : "Rename failed" } : prev,
      )
    }
  }, [rename, renamePath, t, refreshParent])

  const openDelete = useCallback((entry: PodFileEntry) => {
    setDel({ entry, deleting: false })
  }, [])

  const handleDeleteConfirm = useCallback(async () => {
    if (!del || !deletePath) return
    setDel({ ...del, deleting: true })
    try {
      await deletePath(del.entry.path)
      toast.success(t("terminal.files.deleteSuccess"))
      const target = del.entry
      setDel(null)
      refreshParent(target.path)
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "Delete failed")
      setDel((prev) => (prev ? { ...prev, deleting: false } : prev))
    }
  }, [del, deletePath, t, refreshParent])

  const openMkdir = useCallback((parentDir: string) => {
    setMkdir({ parentDir, draft: "", saving: false, error: null })
  }, [])

  const handleMkdirConfirm = useCallback(async () => {
    if (!mkdir || !createDirectory) return
    const trimmed = mkdir.draft.trim()
    if (!trimmed) {
      setMkdir({ ...mkdir, error: t("terminal.files.renameInvalid") })
      return
    }
    if (trimmed.includes("/") || trimmed === "." || trimmed === "..") {
      setMkdir({ ...mkdir, error: t("terminal.files.renameInvalid") })
      return
    }
    const parent = mkdir.parentDir
    const targetPath = parent === "/" ? `/${trimmed}` : `${parent}/${trimmed}`
    setMkdir({ ...mkdir, saving: true, error: null })
    try {
      await createDirectory(targetPath)
      toast.success(t("terminal.files.newFolderSuccess"))
      setMkdir(null)
      if (nodes[parent]) {
        loadDirectory(parent)
      }
    } catch (err) {
      setMkdir((prev) =>
        prev
          ? { ...prev, saving: false, error: err instanceof Error ? err.message : "Create failed" }
          : prev,
      )
    }
  }, [mkdir, createDirectory, t, nodes, loadDirectory])

  const rootState = nodes[rootPath]
  const rootUploading = !!uploadingDirs[rootPath]

  return (
    <div className="flex h-full min-h-0 flex-col bg-background">
      <input
        ref={fileInputRef}
        type="file"
        className="hidden"
        onChange={onFileInputChange}
      />
      <div className="flex h-9 shrink-0 items-center justify-between gap-2 border-b border-sidebar-border px-3">
        <span className="truncate text-xs font-medium text-foreground/80">
          {t("terminal.files.title")}
        </span>
        <div className="flex items-center gap-1">
          {createDirectory ? (
            <button
              type="button"
              onClick={() => openMkdir(rootPath)}
              className="inline-flex size-6 items-center justify-center rounded text-muted-foreground hover:bg-muted hover:text-foreground"
              title={t("terminal.files.newFolder")}
            >
              <FolderPlus className="size-3.5" />
            </button>
          ) : null}
          <button
            type="button"
            onClick={() => loadDirectory(rootPath)}
            className="inline-flex size-6 items-center justify-center rounded text-muted-foreground hover:bg-muted hover:text-foreground"
            title={t("common.refresh")}
          >
            {rootState?.loading ? (
              <Loader2 className="size-3.5 animate-spin" />
            ) : (
              <RefreshCw className="size-3.5" />
            )}
          </button>
        </div>
      </div>
      <RootDropZone
        rootPath={rootPath}
        canUpload={!!uploadFile}
        uploading={rootUploading}
        onUpload={handleUpload}
      >
        <div className="min-h-0 flex-1 overflow-auto py-1 font-mono text-xs">
          {rootState?.error && !rootState.entries ? (
            <div className="px-3 py-2 text-destructive">{rootState.error}</div>
          ) : !rootState?.entries && rootState?.loading ? (
            <div className="flex items-center gap-2 px-3 py-2 text-muted-foreground">
              <Loader2 className="size-3.5 animate-spin" />
              <span>{t("common.loading")}</span>
            </div>
          ) : (
            <TreeLevel
              entries={rootState?.entries ?? []}
              depth={0}
              nodes={nodes}
              uploadingDirs={uploadingDirs}
              onToggle={toggle}
              getDownloadUrl={getDownloadUrl}
              canUpload={!!uploadFile}
              canEdit={!!getFileContent && !!saveFileContent}
              canRename={!!renamePath}
              canDelete={!!deletePath}
              canMkdir={!!createDirectory}
              onTriggerUpload={triggerUpload}
              onUploadFiles={handleUpload}
              onEdit={openEdit}
              onRename={openRename}
              onDelete={openDelete}
              onMkdir={openMkdir}
            />
          )}
        </div>
      </RootDropZone>

      <Dialog open={!!edit} onOpenChange={(open) => !open && setEdit(null)}>
        <DialogContent className="max-w-4xl">
          <DialogHeader>
            <DialogTitle className="font-mono text-sm">
              {edit ? pathBaseName(edit.path) : ""}
            </DialogTitle>
            <DialogDescription className="font-mono text-[11px] text-muted-foreground break-all">
              {edit?.path}
            </DialogDescription>
          </DialogHeader>
          <div className="h-[60vh] overflow-hidden rounded border">
            {edit?.loading ? (
              <div className="flex h-full items-center justify-center text-muted-foreground gap-2 text-sm">
                <Loader2 className="size-4 animate-spin" />
                <span>{t("common.loading")}</span>
              </div>
            ) : edit?.error ? (
              <div className="flex h-full items-center justify-center text-destructive text-sm">
                {edit.error}
              </div>
            ) : edit ? (
              <MonacoEditor
                height="100%"
                language={edit.language}
                theme={editorTheme}
                value={edit.draft}
                onChange={(value) =>
                  setEdit((prev) => (prev ? { ...prev, draft: value ?? "" } : prev))
                }
                options={{
                  minimap: { enabled: false },
                  scrollBeyondLastLine: false,
                  automaticLayout: true,
                  wordWrap: "on",
                }}
              />
            ) : null}
          </div>
          <DialogFooter>
            <Button variant="outline" size="sm" onClick={() => setEdit(null)} disabled={edit?.saving}>
              {t("common.cancel")}
            </Button>
            <Button
              size="sm"
              onClick={handleSaveEdit}
              disabled={
                !edit ||
                edit.loading ||
                edit.saving ||
                edit.draft === edit.original ||
                !!edit.error
              }
            >
              {edit?.saving ? (
                <>
                  <Loader2 className="size-3.5 animate-spin" />
                  {t("terminal.files.saving")}
                </>
              ) : (
                t("terminal.files.save")
              )}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog
        open={!!rename}
        onOpenChange={(open) => {
          if (!open && !rename?.saving) setRename(null)
        }}
      >
        <DialogContent className="max-w-md">
          <DialogHeader>
            <DialogTitle>{t("terminal.files.rename")}</DialogTitle>
            <DialogDescription className="font-mono text-[11px] break-all">
              {rename?.entry.path}
            </DialogDescription>
          </DialogHeader>
          <Input
            value={rename?.draft ?? ""}
            onChange={(e) => setRename((prev) => (prev ? { ...prev, draft: e.target.value, error: null } : prev))}
            onKeyDown={(e) => {
              if (e.key === "Enter") {
                e.preventDefault()
                handleRenameConfirm()
              }
            }}
            autoFocus
            disabled={rename?.saving}
          />
          {rename?.error && (
            <div className="text-xs text-destructive">{rename.error}</div>
          )}
          <DialogFooter>
            <Button variant="outline" size="sm" onClick={() => setRename(null)} disabled={rename?.saving}>
              {t("common.cancel")}
            </Button>
            <Button
              size="sm"
              onClick={handleRenameConfirm}
              disabled={
                !rename ||
                rename.saving ||
                !rename.draft.trim() ||
                rename.draft.trim() === rename.entry.name
              }
            >
              {rename?.saving ? (
                <>
                  <Loader2 className="size-3.5 animate-spin" />
                  {t("terminal.files.saving")}
                </>
              ) : (
                t("common.confirm")
              )}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <AlertDialog
        open={!!del}
        onOpenChange={(open) => {
          if (!open && !del?.deleting) setDel(null)
        }}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t("terminal.files.deleteConfirmTitle")}</AlertDialogTitle>
            <AlertDialogDescription>
              <span className="block">{t("terminal.files.deleteConfirmBody")}</span>
              <span className="mt-2 block break-all font-mono text-[11px]">{del?.entry.path}</span>
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={del?.deleting}>{t("common.cancel")}</AlertDialogCancel>
            <AlertDialogAction
              onClick={(e) => {
                e.preventDefault()
                handleDeleteConfirm()
              }}
              disabled={del?.deleting}
              className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
            >
              {del?.deleting ? (
                <>
                  <Loader2 className="size-3.5 animate-spin" />
                  {t("terminal.files.saving")}
                </>
              ) : (
                t("terminal.files.delete")
              )}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      <Dialog
        open={!!mkdir}
        onOpenChange={(open) => {
          if (!open && !mkdir?.saving) setMkdir(null)
        }}
      >
        <DialogContent className="max-w-md">
          <DialogHeader>
            <DialogTitle>{t("terminal.files.newFolderTitle")}</DialogTitle>
            <DialogDescription className="font-mono text-[11px] break-all">
              {mkdir?.parentDir}
            </DialogDescription>
          </DialogHeader>
          <Input
            value={mkdir?.draft ?? ""}
            placeholder={t("terminal.files.newFolderPlaceholder")}
            onChange={(e) => setMkdir((prev) => (prev ? { ...prev, draft: e.target.value, error: null } : prev))}
            onKeyDown={(e) => {
              if (e.key === "Enter") {
                e.preventDefault()
                handleMkdirConfirm()
              }
            }}
            autoFocus
            disabled={mkdir?.saving}
          />
          {mkdir?.error && (
            <div className="text-xs text-destructive">{mkdir.error}</div>
          )}
          <DialogFooter>
            <Button variant="outline" size="sm" onClick={() => setMkdir(null)} disabled={mkdir?.saving}>
              {t("common.cancel")}
            </Button>
            <Button
              size="sm"
              onClick={handleMkdirConfirm}
              disabled={!mkdir || mkdir.saving || !mkdir.draft.trim()}
            >
              {mkdir?.saving ? (
                <>
                  <Loader2 className="size-3.5 animate-spin" />
                  {t("terminal.files.saving")}
                </>
              ) : (
                t("common.confirm")
              )}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}

interface RootDropZoneProps {
  rootPath: string
  canUpload: boolean
  uploading: boolean
  onUpload: (parentDir: string, file: File) => Promise<void>
  children: React.ReactNode
}

function RootDropZone({ rootPath, canUpload, uploading, onUpload, children }: RootDropZoneProps) {
  const [isOver, setIsOver] = useState(false)

  if (!canUpload) {
    return <div className="flex min-h-0 flex-1 flex-col">{children}</div>
  }

  return (
    <div
      className={cn(
        "flex min-h-0 flex-1 flex-col relative",
        isOver && "ring-2 ring-inset ring-primary/60",
      )}
      onDragEnter={(e) => {
        if (e.dataTransfer.types.includes("Files")) {
          e.preventDefault()
          setIsOver(true)
        }
      }}
      onDragOver={(e) => {
        if (e.dataTransfer.types.includes("Files")) {
          e.preventDefault()
          e.dataTransfer.dropEffect = "copy"
        }
      }}
      onDragLeave={(e) => {
        if (e.currentTarget === e.target) {
          setIsOver(false)
        }
      }}
      onDrop={(e) => {
        if (!e.dataTransfer.types.includes("Files")) return
        e.preventDefault()
        setIsOver(false)
        const file = e.dataTransfer.files?.[0]
        if (file) {
          onUpload(rootPath, file)
        }
      }}
    >
      {children}
      {uploading && (
        <div className="pointer-events-none absolute inset-x-0 bottom-0 flex items-center gap-2 border-t bg-background/90 px-3 py-1 text-[11px] text-muted-foreground">
          <Loader2 className="size-3 animate-spin" />
          <span>Uploading…</span>
        </div>
      )}
    </div>
  )
}

interface TreeLevelProps {
  entries: PodFileEntry[]
  depth: number
  nodes: Record<string, NodeState>
  uploadingDirs: Record<string, boolean>
  onToggle: (entry: PodFileEntry) => void
  getDownloadUrl: (path: string) => string
  canUpload: boolean
  canEdit: boolean
  canRename: boolean
  canDelete: boolean
  canMkdir: boolean
  onTriggerUpload: (parentDir: string) => void
  onUploadFiles: (parentDir: string, file: File) => Promise<void>
  onEdit: (entry: PodFileEntry) => void
  onRename: (entry: PodFileEntry) => void
  onDelete: (entry: PodFileEntry) => void
  onMkdir: (parentDir: string) => void
}

function TreeLevel({
  entries,
  depth,
  nodes,
  uploadingDirs,
  onToggle,
  getDownloadUrl,
  canUpload,
  canEdit,
  canRename,
  canDelete,
  canMkdir,
  onTriggerUpload,
  onUploadFiles,
  onEdit,
  onRename,
  onDelete,
  onMkdir,
}: TreeLevelProps) {
  if (entries.length === 0) {
    return (
      <div
        className="px-3 py-1 italic text-muted-foreground"
        style={{ paddingLeft: 12 + depth * 12 }}
      >
        (empty)
      </div>
    )
  }
  return (
    <ul className="select-none">
      {entries.map((entry) => (
        <TreeRow
          key={entry.path}
          entry={entry}
          depth={depth}
          nodes={nodes}
          uploadingDirs={uploadingDirs}
          onToggle={onToggle}
          getDownloadUrl={getDownloadUrl}
          canUpload={canUpload}
          canEdit={canEdit}
          canRename={canRename}
          canDelete={canDelete}
          canMkdir={canMkdir}
          onTriggerUpload={onTriggerUpload}
          onUploadFiles={onUploadFiles}
          onEdit={onEdit}
          onRename={onRename}
          onDelete={onDelete}
          onMkdir={onMkdir}
        />
      ))}
    </ul>
  )
}

interface TreeRowProps {
  entry: PodFileEntry
  depth: number
  nodes: Record<string, NodeState>
  uploadingDirs: Record<string, boolean>
  onToggle: (entry: PodFileEntry) => void
  getDownloadUrl: (path: string) => string
  canUpload: boolean
  canEdit: boolean
  canRename: boolean
  canDelete: boolean
  canMkdir: boolean
  onTriggerUpload: (parentDir: string) => void
  onUploadFiles: (parentDir: string, file: File) => Promise<void>
  onEdit: (entry: PodFileEntry) => void
  onRename: (entry: PodFileEntry) => void
  onDelete: (entry: PodFileEntry) => void
  onMkdir: (parentDir: string) => void
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  const units = ["KB", "MB", "GB", "TB"]
  let value = bytes / 1024
  let unitIndex = 0
  while (value >= 1024 && unitIndex < units.length - 1) {
    value /= 1024
    unitIndex += 1
  }
  return `${value < 10 ? value.toFixed(1) : Math.round(value)} ${units[unitIndex]}`
}

function TreeRow({
  entry,
  depth,
  nodes,
  uploadingDirs,
  onToggle,
  getDownloadUrl,
  canUpload,
  canEdit,
  canRename,
  canDelete,
  canMkdir,
  onTriggerUpload,
  onUploadFiles,
  onEdit,
  onRename,
  onDelete,
  onMkdir,
}: TreeRowProps) {
  const { t } = useLanguage()
  const isDir = entry.type === "DIRECTORY" || entry.type === "SYMLINK_DIRECTORY"
  const isFile = entry.type === "FILE" || entry.type === "SYMLINK_FILE"
  const state = nodes[entry.path]
  const expanded = isDir && state?.expanded
  const indent = 8 + depth * 12
  const showSize = isFile && entry.size != null
  const dirUploading = isDir && uploadingDirs[entry.path]
  const [dragOver, setDragOver] = useState(false)

  const handleCopy = async (text: string) => {
    try {
      await navigator.clipboard.writeText(text)
      toast.success(t("terminal.files.copied"))
    } catch {
      toast.error("Copy failed")
    }
  }

  const handleDownload = () => {
    try {
      const url = getDownloadUrl(entry.path)
      const a = Object.assign(document.createElement("a"), { href: url, download: entry.name })
      document.body.appendChild(a)
      a.click()
      a.remove()
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "Download failed")
    }
  }

  const handleDirDrop = (event: React.DragEvent<HTMLDivElement>) => {
    if (!isDir || !canUpload) return
    if (!event.dataTransfer.types.includes("Files")) return
    event.preventDefault()
    event.stopPropagation()
    setDragOver(false)
    const file = event.dataTransfer.files?.[0]
    if (file) {
      if (!state?.expanded) {
        onToggle(entry)
      }
      onUploadFiles(entry.path, file)
    }
  }

  return (
    <li>
      <ContextMenu>
        <ContextMenuTrigger asChild>
          <div
            className={cn("group/ctx", dragOver && "bg-primary/10")}
            onDragEnter={(e) => {
              if (isDir && canUpload && e.dataTransfer.types.includes("Files")) {
                e.preventDefault()
                e.stopPropagation()
                setDragOver(true)
              }
            }}
            onDragOver={(e) => {
              if (isDir && canUpload && e.dataTransfer.types.includes("Files")) {
                e.preventDefault()
                e.stopPropagation()
                e.dataTransfer.dropEffect = "copy"
              }
            }}
            onDragLeave={(e) => {
              if (e.currentTarget === e.target) {
                setDragOver(false)
              }
            }}
            onDrop={handleDirDrop}
          >
            <TooltipProvider delayDuration={300}>
              <Tooltip>
                <TooltipTrigger asChild>
                  <button
                    type="button"
                    onClick={() => onToggle(entry)}
                    onDoubleClick={() => {
                      if (isFile && canEdit) onEdit(entry)
                    }}
                    className={cn(
                      "group flex w-full items-center gap-1.5 px-2 py-0.5 text-left transition-colors hover:bg-muted group-data-[state=open]/ctx:bg-muted",
                      isDir ? "cursor-pointer" : "cursor-default text-muted-foreground",
                    )}
                    style={{ paddingLeft: indent }}
                  >
                    <span className="flex w-3.5 shrink-0 items-center justify-center">
                      {isDir ? (
                        state?.loading || dirUploading ? (
                          <Loader2 className="size-3 animate-spin text-muted-foreground" />
                        ) : (
                          <ChevronRight
                            className={cn(
                              "size-3 text-muted-foreground transition-transform",
                              expanded && "rotate-90",
                            )}
                          />
                        )
                      ) : null}
                    </span>
                    <span className="flex w-4 shrink-0 items-center justify-center">
                      {entry.type === "SYMLINK_DIRECTORY" ? (
                        <FolderSymlink className="size-3.5 text-violet-500" />
                      ) : entry.type === "SYMLINK_FILE" ? (
                        <FileSymlink className="size-3.5 text-violet-500" />
                      ) : isDir ? (
                        <Folder className="size-3.5 text-sky-500" />
                      ) : (
                        <FileIcon className="size-3.5 text-muted-foreground" />
                      )}
                    </span>
                    <span className="min-w-0 flex-1 truncate">{entry.name}</span>
                    {showSize ? (
                      <span className="shrink-0 pl-2 tabular-nums text-[11px] text-muted-foreground/70">
                        {formatBytes(entry.size!)}
                      </span>
                    ) : null}
                  </button>
                </TooltipTrigger>
                <TooltipContent
                  side="right"
                  align="center"
                  avoidCollisions={false}
                  className="max-w-md break-all whitespace-normal"
                >
                  <div className="font-medium">{entry.name}</div>
                  <div className="text-[11px] opacity-70">{entry.path}</div>
                </TooltipContent>
              </Tooltip>
            </TooltipProvider>
          </div>
        </ContextMenuTrigger>
        <ContextMenuContent>
          {isFile && canEdit && (
            <>
              <ContextMenuItem onClick={() => onEdit(entry)} className="cursor-pointer">
                {t("terminal.files.edit")}
              </ContextMenuItem>
              <ContextMenuSeparator />
            </>
          )}
          {isDir && canMkdir && (
            <ContextMenuItem onClick={() => onMkdir(entry.path)} className="cursor-pointer">
              {t("terminal.files.newFolderHere")}
            </ContextMenuItem>
          )}
          {isDir && canUpload && (
            <ContextMenuItem onClick={() => onTriggerUpload(entry.path)} className="cursor-pointer">
              {t("terminal.files.uploadHere")}
            </ContextMenuItem>
          )}
          {isDir && (canMkdir || canUpload) && <ContextMenuSeparator />}
          <ContextMenuItem onClick={() => handleCopy(entry.path)} className="cursor-pointer">
            {t("terminal.files.copyPath")}
          </ContextMenuItem>
          <ContextMenuItem onClick={() => handleCopy(entry.name)} className="cursor-pointer">
            {t("terminal.files.copyName")}
          </ContextMenuItem>
          {isFile && (
            <ContextMenuItem onClick={handleDownload} className="cursor-pointer">
              {t("terminal.files.download")}
            </ContextMenuItem>
          )}
          {(canRename || canDelete) && <ContextMenuSeparator />}
          {canRename && (
            <ContextMenuItem onClick={() => onRename(entry)} className="cursor-pointer">
              {t("terminal.files.rename")}
            </ContextMenuItem>
          )}
          {canDelete && (
            <ContextMenuItem
              onClick={() => onDelete(entry)}
              className="cursor-pointer text-destructive focus:text-destructive"
            >
              {t("terminal.files.delete")}
            </ContextMenuItem>
          )}
        </ContextMenuContent>
      </ContextMenu>
      {isDir && expanded ? (
        state?.error ? (
          <div className="px-3 py-1 text-destructive" style={{ paddingLeft: indent + 16 }}>
            {state.error}
          </div>
        ) : state?.entries ? (
          <TreeLevel
            entries={state.entries}
            depth={depth + 1}
            nodes={nodes}
            uploadingDirs={uploadingDirs}
            onToggle={onToggle}
            getDownloadUrl={getDownloadUrl}
            canUpload={canUpload}
            canEdit={canEdit}
            canRename={canRename}
            canDelete={canDelete}
            canMkdir={canMkdir}
            onTriggerUpload={onTriggerUpload}
            onUploadFiles={onUploadFiles}
            onEdit={onEdit}
            onRename={onRename}
            onDelete={onDelete}
            onMkdir={onMkdir}
          />
        ) : null
      ) : null}
    </li>
  )
}

export { detectLanguage, pathBaseName }
