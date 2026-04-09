"use client"

import { useState, useEffect, useCallback, useRef } from "react"
import { useRouter } from "next/navigation"
import {
  Command,
  CommandInput,
  CommandList,
  CommandEmpty,
  CommandGroup,
  CommandItem,
  CommandSeparator,
} from "@/components/ui/command"
import {
  Dialog,
  DialogContent,
  DialogTitle,
} from "@/components/ui/dialog"
import { Application } from "@/lib/api/types"
import { searchAllApplications } from "@/lib/api/applications"
import { Activity, Rocket, Loader2, Keyboard, Terminal, GitBranch } from "lucide-react"
import { useLanguage } from "@/contexts/language-context"
import { useRecentAppStore } from "@/store/recent-app"

type CommandType = "status" | "deploy" | "ide" | "pipeline" | null

interface CommandOption {
  id: string
  name: string
  description: string
  icon: React.ReactNode
}

export function CommandPalette() {
  const [open, setOpen] = useState(false)
  const [inputValue, setInputValue] = useState("")
  const [selectedCommand, setSelectedCommand] = useState<CommandType>(null)
  const [applications, setApplications] = useState<Application[]>([])
  const [loading, setLoading] = useState(false)
  const [searchQuery, setSearchQuery] = useState("")
  const router = useRouter()
  const { t } = useLanguage()
  const inputRef = useRef<HTMLInputElement>(null)
  const { recentApp, setRecentApp } = useRecentAppStore()

  const commands: CommandOption[] = [
    {
      id: "status",
      name: "Status",
      description: t("cmd.statusDesc"),
      icon: <Activity className="w-4 h-4" />,
    },
    {
      id: "deploy",
      name: "Deploy",
      description: t("cmd.deployDesc"),
      icon: <Rocket className="w-4 h-4" />,
    },
    {
      id: "ide",
      name: "IDE",
      description: t("cmd.ideDesc"),
      icon: <Terminal className="w-4 h-4" />,
    },
    {
      id: "pipeline",
      name: "Pipeline",
      description: t("cmd.pipelineDesc"),
      icon: <GitBranch className="w-4 h-4" />,
    },
  ]

  // Handle keyboard shortcut
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      // Open with / key, but not when typing in input/textarea
      if (e.key === "/" && !e.metaKey && !e.ctrlKey) {
        const target = e.target as HTMLElement
        if (
          target.tagName === "INPUT" ||
          target.tagName === "TEXTAREA" ||
          target.isContentEditable
        ) {
          return
        }
        e.preventDefault()
        setOpen(true)
      }

      // Close with Escape
      if (e.key === "Escape" && open) {
        setOpen(false)
      }
    }

    document.addEventListener("keydown", handleKeyDown)
    return () => document.removeEventListener("keydown", handleKeyDown)
  }, [open])

  // Focus input when opened
  useEffect(() => {
    if (open && inputRef.current) {
      setTimeout(() => inputRef.current?.focus(), 100)
    }
  }, [open])

  // Reset state when closed
  useEffect(() => {
    if (!open) {
      setInputValue("")
      setSelectedCommand(null)
      setApplications([])
      setSearchQuery("")
    }
  }, [open])

  const performSearch = useCallback(async (keyword: string) => {
    setLoading(true)
    try {
      const res = await searchAllApplications(keyword)
      setApplications(res.data)
    } catch (error) {
      console.error("Failed to search applications:", error)
      setApplications([])
    } finally {
      setLoading(false)
    }
  }, [])

  // Debounced search
  useEffect(() => {
    if (!selectedCommand) return

    const query = inputValue.trim()
    if (query === searchQuery) return

    const timer = setTimeout(() => {
      setSearchQuery(query)
      performSearch(query)
    }, 150)

    return () => clearTimeout(timer)
  }, [inputValue, selectedCommand, searchQuery, performSearch])

  // 初始加载空搜索以显示所有应用
  useEffect(() => {
    if (selectedCommand && applications.length === 0 && !loading && !searchQuery) {
      performSearch("")
    }
  }, [selectedCommand, applications.length, loading, searchQuery, performSearch])

  const handleCommandSelect = (commandId: string) => {
    setSelectedCommand(commandId as CommandType)
    setInputValue("")
    setSearchQuery("")
    setApplications([])
  }

  // 过滤搜索结果，排除最近使用中的应用
  const filteredApplications = recentApp
    ? applications.filter(
        (app) =>
          !(app.namespace === recentApp.namespace && app.name === recentApp.name)
      )
    : applications

  const handleAppSelect = (app: Application) => {
    if (!selectedCommand) return

    // Save to store
    setRecentApp({
      namespace: app.namespace,
      name: app.name,
      description: app.description,
    })

    setOpen(false)

    switch (selectedCommand) {
      case "status":
        router.push(`/apps/${app.namespace}/${app.name}/status`)
        break
      case "deploy":
        router.push(`/apps/${app.namespace}/${app.name}/publish`)
        break
      case "ide":
        router.push(`/ide?namespace=${app.namespace}&app=${app.name}`)
        break
      case "pipeline":
        router.push(`/pipelines?namespace=${app.namespace}&app=${app.name}`)
        break
    }
  }

  const handleBackToCommands = () => {
    setSelectedCommand(null)
    setInputValue("")
    setApplications([])
    setSearchQuery("")
  }

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === "Backspace" && inputValue === "" && selectedCommand) {
      e.preventDefault()
      handleBackToCommands()
    }
  }

  return (
    <>
      {/* Hint button */}
      <button
        onClick={() => setOpen(true)}
        className="fixed bottom-4 left-1/2 -translate-x-1/2 z-30 hidden md:flex items-center gap-2 text-xs text-muted-foreground bg-background/80 backdrop-blur-sm px-3 py-1.5 rounded-full border shadow-sm hover:bg-accent hover:text-accent-foreground transition-colors cursor-pointer"
      >
        <Keyboard className="w-3.5 h-3.5" />
        <span>{t("cmd.hint")}</span>
        <kbd className="bg-muted px-1.5 py-0.5 rounded text-[10px] font-mono">/</kbd>
      </button>

      {/* Command palette dialog */}
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent showCloseButton={false} className="p-0 gap-0 max-w-2xl overflow-hidden fixed top-[20%] translate-y-0 -translate-x-1/2">
          <DialogTitle className="sr-only">{t("cmd.title")}</DialogTitle>
          <Command
            className="rounded-lg border shadow-md"
            filter={(value, search) => {
              // Case-insensitive matching for commands and apps
              const normalizedValue = value.toLowerCase()
              const normalizedSearch = search.toLowerCase()
              if (normalizedValue.includes(normalizedSearch)) {
                return 1
              }
              return 0
            }}
          >
            <div className="flex items-center border-b px-3">
              {selectedCommand && (
                <div className="flex items-center gap-1 mr-2">
                  {(() => {
                    const cmd = commands.find(c => c.id === selectedCommand)
                    return cmd ? (
                      <>
                        <span className="text-muted-foreground">{cmd.icon}</span>
                        <span className="text-muted-foreground text-xs font-semibold">
                          {cmd.name}
                        </span>
                      </>
                    ) : null
                  })()}
                </div>
              )}
              <CommandInput
                ref={inputRef}
                placeholder={
                  selectedCommand
                    ? t("cmd.searchAppPlaceholder")
                    : t("cmd.placeholder")
                }
                value={inputValue}
                onValueChange={setInputValue}
                onKeyDown={handleKeyDown}
                className="flex-1 h-12"
              />
              {loading && (
                <Loader2 className="w-4 h-4 animate-spin text-muted-foreground" />
              )}
            </div>
            <CommandList className="max-h-[400px]">
              {!selectedCommand ? (
                <>
                  <CommandEmpty>{t("cmd.noCommands")}</CommandEmpty>
                  <CommandGroup heading={t("cmd.availableCommands")}>
                    {commands.map((cmd) => (
                      <CommandItem
                        key={cmd.id}
                        value={cmd.id}
                        onSelect={() => handleCommandSelect(cmd.id)}
                        className="flex items-center gap-2 py-3 cursor-pointer"
                      >
                        {cmd.icon}
                        <span className="font-medium">{cmd.name}</span>
                        <span className="text-muted-foreground ml-2">
                          {cmd.description}
                        </span>
                      </CommandItem>
                    ))}
                  </CommandGroup>
                </>
              ) : (
                <>
                  {/* Recent app section - always show if exists, not affected by loading */}
                  {recentApp && (
                    <CommandGroup heading={t("cmd.recent")}>
                      <CommandItem
                        key={`${recentApp.namespace}/${recentApp.name}`}
                        value={`${recentApp.namespace}/${recentApp.name}`}
                        onSelect={() =>
                          handleAppSelect({
                            id: `${recentApp.namespace}/${recentApp.name}`,
                            workspaceId: "",
                            name: recentApp.name,
                            description: recentApp.description,
                            namespace: recentApp.namespace,
                          })
                        }
                        className="flex flex-col items-start gap-1 py-3 cursor-pointer"
                      >
                        <div className="flex items-center gap-2 w-full">
                          <span className="font-medium truncate">
                            {recentApp.name}
                          </span>
                          <span className="text-xs text-muted-foreground bg-muted px-1.5 py-0.5 rounded">
                            {recentApp.namespace}
                          </span>
                        </div>
                        {recentApp.description && (
                          <span className="text-sm text-muted-foreground truncate w-full">
                            {recentApp.description}
                          </span>
                        )}
                      </CommandItem>
                    </CommandGroup>
                  )}
                  {/* Separator between recent and search results */}
                  {recentApp && filteredApplications.length > 0 && (
                    <CommandSeparator />
                  )}
                  {/* Search results section - keep showing old results while loading */}
                  {filteredApplications.length > 0 && (
                    <CommandGroup
                      heading={`${t("cmd.searchResults")} (${filteredApplications.length})`}
                    >
                      {filteredApplications.map((app) => (
                        <CommandItem
                          key={`${app.namespace}/${app.name}`}
                          value={`${app.namespace}/${app.name}`}
                          onSelect={() => handleAppSelect(app)}
                          className="flex flex-col items-start gap-1 py-3 cursor-pointer"
                        >
                          <div className="flex items-center gap-2 w-full">
                            <span className="font-medium truncate">
                              {app.name}
                            </span>
                            <span className="text-xs text-muted-foreground bg-muted px-1.5 py-0.5 rounded">
                              {app.namespace}
                            </span>
                          </div>
                          {app.description && (
                            <span className="text-sm text-muted-foreground truncate w-full">
                              {app.description}
                            </span>
                          )}
                        </CommandItem>
                      ))}
                    </CommandGroup>
                  )}
                  {/* Empty state - only show when not loading and no results */}
                  {!loading && applications.length === 0 && !recentApp && (
                    <CommandEmpty>{t("cmd.noApps")}</CommandEmpty>
                  )}
                </>
              )}
            </CommandList>
            <div className="flex items-center justify-between px-3 py-2 border-t text-xs text-muted-foreground">
              <div className="flex items-center gap-4">
                <span className="flex items-center gap-1">
                  <kbd className="bg-muted px-1.5 py-0.5 rounded">/</kbd>
                  {t("cmd.toggle")}
                </span>
                {selectedCommand && (
                  <span className="flex items-center gap-1">
                    <kbd className="bg-muted px-1.5 py-0.5 rounded">←</kbd>
                    {t("cmd.back")}
                  </span>
                )}
                <span className="flex items-center gap-1">
                  <kbd className="bg-muted px-1.5 py-0.5 rounded">esc</kbd>
                  {t("cmd.close")}
                </span>
              </div>
              <span className="flex items-center gap-1">
                <kbd className="bg-muted px-1.5 py-0.5 rounded">↵</kbd>
                {t("cmd.select")}
              </span>
            </div>
          </Command>
        </DialogContent>
      </Dialog>
    </>
  )
}
