"use client"

import { RefreshCw, X } from "lucide-react"

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
import { ApplicationResourceViewer, useApplicationResources } from "./application-resource-viewer"

interface ApplicationResourceDrawerProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  namespace: string
  applicationName: string
  environment: string
}

/**
 * Right-hand drawer showing the application's live Kubernetes manifests for
 * one environment. Resources are fetched only while the drawer is open.
 */
export function ApplicationResourceDrawer({
  open,
  onOpenChange,
  namespace,
  applicationName,
  environment,
}: ApplicationResourceDrawerProps) {
  const { t } = useLanguage()
  const { resources, loading, error, reload } = useApplicationResources(
    open ? namespace : undefined,
    applicationName,
    environment,
  )

  return (
    <Drawer open={open} onOpenChange={onOpenChange} swipeDirection="right">
      <DrawerContent className="data-[swipe-axis=x]:sm:[--drawer-content-width:min(64rem,92vw)]">
        <DrawerHeader className="border-b pb-4 md:text-left">
          <div className="flex items-start justify-between gap-4">
            <div className="min-w-0">
              <DrawerTitle>{t("apps.status.resources")}</DrawerTitle>
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
                aria-label={t("apps.status.resourcesRefresh")}
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

        <div className="flex min-h-0 flex-1 flex-col p-4">
          <ApplicationResourceViewer
            environment={environment}
            resources={resources}
            loading={loading}
            error={error}
            fill
          />
        </div>
      </DrawerContent>
    </Drawer>
  )
}
