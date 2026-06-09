"use client"

import { useEffect, useState } from "react"
import dayjs from "dayjs"
import { AlertCircle, ChevronRight, Info } from "lucide-react"
import { getApplicationEvents } from "@/lib/api/applications"
import { ApplicationEvent } from "@/lib/api/types"
import { Badge } from "@/components/ui/badge"
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import { useLanguage } from "@/contexts/language-context"

interface ApplicationEventsPanelProps {
  namespace: string
  applicationName: string
  environmentName: string
  since?: string
  limit?: number
  refreshIntervalMs?: number
}

export function ApplicationEventsPanel({
  namespace,
  applicationName,
  environmentName,
  since,
  limit = 200,
  refreshIntervalMs = 5000,
}: ApplicationEventsPanelProps) {
  const { t } = useLanguage()
  const [events, setEvents] = useState<ApplicationEvent[]>([])
  const [loading, setLoading] = useState(false)
  const [open, setOpen] = useState(false)
  const effectiveLimit = open ? limit : 1

  useEffect(() => {
    setEvents([])
    setLoading(false)
  }, [namespace, applicationName, environmentName, since, limit])

  useEffect(() => {
    if (!environmentName) return

    let cancelled = false
    const loadEvents = async (showLoading = false) => {
      if (showLoading) setLoading(true)
      try {
        const response = await getApplicationEvents(namespace, applicationName, environmentName, { since, limit: effectiveLimit })
        if (!cancelled) setEvents(response.data ?? [])
      } catch {
        if (!cancelled) setEvents([])
      } finally {
        if (!cancelled && showLoading) setLoading(false)
      }
    }

    loadEvents(true)
    const intervalId = setInterval(() => loadEvents(false), refreshIntervalMs)
    return () => {
      cancelled = true
      clearInterval(intervalId)
    }
  }, [namespace, applicationName, environmentName, since, effectiveLimit, refreshIntervalMs])

  const warningCount = events.filter((event) => event.type === "Warning").length
  const latestEvent = events[0]
  const latestResource = latestEvent ? [latestEvent.resourceKind, latestEvent.resourceName].filter(Boolean).join("/") : ""

  const renderTypeBadge = (event: ApplicationEvent) => {
    const isWarning = event.type === "Warning"
    return (
      <Badge variant={isWarning ? "destructive" : "secondary"} className="gap-1">
        {isWarning ? <AlertCircle className="size-3" /> : <Info className="size-3" />}
        {event.type || "-"}
      </Badge>
    )
  }

  return (
    <Collapsible open={open} onOpenChange={setOpen} className="min-w-0 rounded-md border">
      <CollapsibleTrigger className="flex w-full items-center justify-between gap-3 px-3 py-2 text-left transition-colors hover:bg-muted/50">
        <div className="flex min-w-0 items-center gap-2">
          <ChevronRight className={`size-4 shrink-0 transition-transform ${open ? "rotate-90" : ""}`} />
          <span className="font-semibold">{t("apps.events.title")}</span>
          {open && events.length > 0 && (
            <Badge variant="secondary" className="shrink-0">
              {events.length}
            </Badge>
          )}
          {open && warningCount > 0 && (
            <Badge variant="destructive" className="shrink-0">
              {warningCount} Warning
            </Badge>
          )}
          {!open && latestEvent?.type === "Warning" && (
            <Badge variant="destructive" className="shrink-0">
              Warning
            </Badge>
          )}
        </div>
        {since && (
          <span className="shrink-0 text-xs text-muted-foreground">
            {t("apps.events.since")} {dayjs(since).format("YYYY-MM-DD HH:mm:ss")}
          </span>
        )}
      </CollapsibleTrigger>
      {!open && (
        <div className="grid grid-cols-[auto_auto_minmax(0,1fr)] items-center gap-3 border-t px-3 py-2 text-sm">
          {loading && events.length === 0 ? (
            <div className="col-span-3 text-muted-foreground">{t("common.loading")}</div>
          ) : latestEvent ? (
            <>
              <span className="text-xs text-muted-foreground whitespace-nowrap">
                {dayjs(latestEvent.time).format("MM-DD HH:mm:ss")}
              </span>
              {renderTypeBadge(latestEvent)}
              <div className="min-w-0 truncate">
                <span className="font-medium">{latestEvent.reason || "-"}</span>
                <span className="text-muted-foreground">
                  {latestResource ? ` · ${latestResource}` : ""}
                  {latestEvent.message ? ` · ${latestEvent.message}` : ""}
                </span>
              </div>
            </>
          ) : (
            <div className="col-span-3 text-muted-foreground">{t("apps.events.empty")}</div>
          )}
        </div>
      )}
      <CollapsibleContent className="pt-2">
        <div className="border-t overflow-x-auto">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead className="h-9 px-3 w-36">{t("apps.events.time")}</TableHead>
                <TableHead className="h-9 px-3 w-24">{t("apps.events.type")}</TableHead>
                <TableHead className="h-9 px-3 w-40">{t("apps.events.resource")}</TableHead>
                <TableHead className="h-9 px-3 w-36">{t("apps.events.reason")}</TableHead>
                <TableHead className="h-9 px-3">{t("apps.events.message")}</TableHead>
                <TableHead className="h-9 px-3 w-20 text-right">{t("apps.events.count")}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {loading && events.length === 0 && (
                <TableRow>
                  <TableCell colSpan={6} className="h-20 text-center text-muted-foreground">
                    {t("common.loading")}
                  </TableCell>
                </TableRow>
              )}
              {!loading && events.length === 0 && (
                <TableRow>
                  <TableCell colSpan={6} className="h-20 text-center text-muted-foreground">
                    {t("apps.events.empty")}
                  </TableCell>
                </TableRow>
              )}
              {events.map((event, index) => {
                const resource = [event.resourceKind, event.resourceName].filter(Boolean).join("/")
                return (
                  <TableRow key={`${event.time}-${event.resourceKind}-${event.resourceName}-${event.reason}-${index}`}>
                    <TableCell className="px-3 py-2 text-xs text-muted-foreground whitespace-nowrap">
                      {dayjs(event.time).format("MM-DD HH:mm:ss")}
                    </TableCell>
                    <TableCell className="px-3 py-2">
                      {renderTypeBadge(event)}
                    </TableCell>
                    <TableCell className="px-3 py-2 font-mono text-xs break-all">
                      {resource || "-"}
                    </TableCell>
                    <TableCell className="px-3 py-2 font-medium break-all">
                      {event.reason || "-"}
                    </TableCell>
                    <TableCell className="px-3 py-2 text-sm text-muted-foreground break-words min-w-64">
                      {event.message || "-"}
                    </TableCell>
                    <TableCell className="px-3 py-2 text-right text-muted-foreground">
                      {event.count ?? 1}
                    </TableCell>
                  </TableRow>
                )
              })}
            </TableBody>
          </Table>
        </div>
      </CollapsibleContent>
    </Collapsible>
  )
}
