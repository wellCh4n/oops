"use client"

import { RefreshCw, X } from "lucide-react"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Drawer,
  DrawerClose,
  DrawerContent,
  DrawerDescription,
  DrawerHeader,
  DrawerTitle,
} from "@/components/ui/drawer"
import { useLanguage } from "@/contexts/language-context"
import { ApplicationEventsList, useApplicationEvents } from "./application-events-panel"

interface ApplicationEventsDrawerProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  namespace: string
  applicationName: string
  environment: string
}

/**
 * Right-hand drawer listing the application's recent Kubernetes events for one
 * environment. Polling only runs while the drawer is open.
 */
export function ApplicationEventsDrawer({
  open,
  onOpenChange,
  namespace,
  applicationName,
  environment,
}: ApplicationEventsDrawerProps) {
  const { t } = useLanguage()
  const { events, loading, reload } = useApplicationEvents({
    namespace,
    applicationName,
    environment,
    limit: 200,
    refreshIntervalMs: 5000,
    enabled: open,
  })
  const warningCount = events.filter((event) => event.type === "Warning").length

  return (
    <Drawer open={open} onOpenChange={onOpenChange} swipeDirection="right">
      <DrawerContent className="data-[swipe-axis=x]:sm:[--drawer-content-width:min(64rem,92vw)]">
        <DrawerHeader className="border-b pb-4 md:text-left">
          <div className="flex items-start justify-between gap-4">
            <div className="min-w-0">
              <div className="flex flex-wrap items-center gap-2">
                <DrawerTitle>{t("apps.events.title")}</DrawerTitle>
                {events.length > 0 && (
                  <Badge variant="secondary" className="shrink-0">
                    {events.length}
                  </Badge>
                )}
                {warningCount > 0 && (
                  <Badge variant="destructive" className="shrink-0">
                    {warningCount} {t("apps.events.type.warning")}
                  </Badge>
                )}
              </div>
              <DrawerDescription className="truncate">
                {applicationName} · {environment}
              </DrawerDescription>
            </div>
            <div className="flex items-center gap-1">
              <Button
                variant="ghost"
                size="icon-sm"
                onClick={reload}
                disabled={loading}
                aria-label={t("apps.events.refresh")}
              >
                <RefreshCw className={loading ? "animate-spin" : undefined} />
              </Button>
              <DrawerClose
                render={<Button variant="ghost" size="icon-sm" aria-label={t("common.close")} />}
              >
                <X />
              </DrawerClose>
            </div>
          </div>
        </DrawerHeader>

        <div className="min-h-0 flex-1 overflow-auto">
          <div className="min-w-3xl">
            <ApplicationEventsList events={events} loading={loading} />
          </div>
        </div>
      </DrawerContent>
    </Drawer>
  )
}
