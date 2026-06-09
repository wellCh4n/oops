"use client"

import { useEffect, useState } from "react"
import dayjs from "dayjs"
import { AlertCircle, ChevronRight, Info } from "lucide-react"
import { getApplicationEvents } from "@/lib/api/applications"
import { ApplicationEvent } from "@/lib/api/types"
import { Badge } from "@/components/ui/badge"
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible"
import { useLanguage } from "@/contexts/language-context"

interface ApplicationEventsPanelProps {
  namespace: string
  applicationName: string
  environmentName: string
  since?: string
  limit?: number
  refreshIntervalMs?: number
  compact?: boolean
}

export function ApplicationEventsPanel({
  namespace,
  applicationName,
  environmentName,
  since,
  limit = 200,
  refreshIntervalMs = 5000,
  compact = false,
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
  const eventTypeLabel = (type?: string | null) => {
    if (type === "Warning") return t("apps.events.type.warning")
    if (type === "Normal") return t("apps.events.type.normal")
    return type || "-"
  }

  const handleOpenChange = (nextOpen: boolean) => {
    setOpen(nextOpen)
    if (nextOpen) {
      setEvents([])
      setLoading(true)
    }
  }

  const renderTypeBadge = (event: ApplicationEvent) => {
    const isWarning = event.type === "Warning"
    return (
      <Badge variant={isWarning ? "destructive" : "secondary"} className="gap-1">
        {isWarning ? <AlertCircle className="size-3" /> : <Info className="size-3" />}
        {eventTypeLabel(event.type)}
      </Badge>
    )
  }

  const renderEmptyState = () => {
    if (loading && events.length === 0) {
      return <div className="px-3 py-8 text-center text-muted-foreground">{t("common.loading")}</div>
    }
    if (events.length === 0) {
      return <div className="px-3 py-8 text-center text-muted-foreground">{t("apps.events.empty")}</div>
    }
    return null
  }

  const renderCompactEvents = () => (
    <div className="border-t">
      {renderEmptyState()}
      {events.map((event, index) => {
        const resource = [event.resourceKind, event.resourceName].filter(Boolean).join("/")
        return (
          <div
            key={`${event.time}-${event.resourceKind}-${event.resourceName}-${event.reason}-${index}`}
            className="border-b px-3 py-3 transition-colors last:border-0 hover:bg-muted/50"
          >
            <div className="flex items-start justify-between gap-2">
              <div className="flex min-w-0 flex-1 flex-wrap items-center gap-x-2 gap-y-1">
                {renderTypeBadge(event)}
                <span className="text-xs text-muted-foreground">
                  {dayjs(event.time).format("MM-DD HH:mm:ss")}
                </span>
                <span className="min-w-0 max-w-full break-all font-mono text-xs text-muted-foreground">
                  {resource || "-"}
                </span>
              </div>
              <Badge variant="outline" className="ml-auto shrink-0">
                x{event.count ?? 1}
              </Badge>
            </div>
            <div className="mt-2 min-w-0">
              <div className="break-words font-medium [overflow-wrap:anywhere]">{event.reason || "-"}</div>
              <div className="mt-1 break-words text-sm text-muted-foreground [overflow-wrap:anywhere]">
                {event.message || "-"}
              </div>
            </div>
          </div>
        )
      })}
    </div>
  )

  const renderTableEvents = () => (
    <div className="border-t">
      <table className="w-full table-fixed text-sm">
        <colgroup>
          <col className="w-[10%]" />
          <col className="w-[10%]" />
          <col className="w-[18%]" />
          <col className="w-[14%]" />
          <col className="w-[40%]" />
          <col className="w-[8%]" />
        </colgroup>
        <thead>
          <tr className="border-b">
            <th className="h-10 px-3 text-left align-middle font-medium">{t("apps.events.time")}</th>
            <th className="h-10 px-3 text-left align-middle font-medium">{t("apps.events.type")}</th>
            <th className="h-10 px-3 text-left align-middle font-medium">{t("apps.events.resource")}</th>
            <th className="h-10 px-3 text-left align-middle font-medium">{t("apps.events.reason")}</th>
            <th className="h-10 px-3 text-left align-middle font-medium">{t("apps.events.message")}</th>
            <th className="h-10 whitespace-nowrap px-3 text-right align-middle font-medium">{t("apps.events.count")}</th>
          </tr>
        </thead>
        <tbody>
          {loading && events.length === 0 && (
            <tr>
              <td colSpan={6} className="h-20 px-3 text-center text-muted-foreground">
                {t("common.loading")}
              </td>
            </tr>
          )}
          {!loading && events.length === 0 && (
            <tr>
              <td colSpan={6} className="h-20 px-3 text-center text-muted-foreground">
                {t("apps.events.empty")}
              </td>
            </tr>
          )}
          {events.map((event, index) => {
            const resource = [event.resourceKind, event.resourceName].filter(Boolean).join("/")
            return (
              <tr
                key={`${event.time}-${event.resourceKind}-${event.resourceName}-${event.reason}-${index}`}
                className="border-b transition-colors last:border-0 hover:bg-muted/50"
              >
                <td className="px-3 py-2 align-top text-xs text-muted-foreground">
                  {dayjs(event.time).format("MM-DD HH:mm:ss")}
                </td>
                <td className="px-3 py-2 align-top">
                  {renderTypeBadge(event)}
                </td>
                <td className="break-words px-3 py-2 align-top font-mono text-xs [overflow-wrap:anywhere]">
                  {resource || "-"}
                </td>
                <td className="break-words px-3 py-2 align-top font-medium [overflow-wrap:anywhere]">
                  {event.reason || "-"}
                </td>
                <td className="break-words px-3 py-2 align-top text-sm text-muted-foreground [overflow-wrap:anywhere]">
                  {event.message || "-"}
                </td>
                <td className="whitespace-nowrap px-3 py-2 text-right align-top text-muted-foreground">
                  {event.count ?? 1}
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )

  return (
    <Collapsible open={open} onOpenChange={handleOpenChange} className="min-w-0 rounded-md border">
      <CollapsibleTrigger className="flex min-h-12 w-full items-center justify-between gap-3 px-3 py-2 text-left transition-colors hover:bg-muted/50">
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
              {warningCount} {t("apps.events.type.warning")}
            </Badge>
          )}
          {!open && latestEvent?.type === "Warning" && (
            <Badge variant="destructive" className="shrink-0">
              {t("apps.events.type.warning")}
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
      <CollapsibleContent>
        {compact ? renderCompactEvents() : renderTableEvents()}
      </CollapsibleContent>
    </Collapsible>
  )
}
