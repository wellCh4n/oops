"use client"

import { useState } from "react"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Switch } from "@/components/ui/switch"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { useLanguage } from "@/contexts/language-context"

export interface HostBasicAuth {
  basicAuthEnabled: boolean
  basicAuthUsername: string
  basicAuthPassword: string
  basicAuthPasswordSet: boolean
}

interface Props {
  onOpenChange: (open: boolean) => void
  host: string
  value: HostBasicAuth
  onConfirm: (value: HostBasicAuth) => void
}

/**
 * Mounted only while open, so the fields simply start from the host's current values — no effect
 * needed to re-sync them.
 */
export function HostBasicAuthDialog({ onOpenChange, host, value, onConfirm }: Props) {
  const { t } = useLanguage()

  const [enabled, setEnabled] = useState(value.basicAuthEnabled)
  const [username, setUsername] = useState(value.basicAuthUsername)
  const [password, setPassword] = useState(value.basicAuthPassword)
  const [error, setError] = useState<string | null>(null)

  // A stored password can never be read back, so an existing one shows as an empty field that
  // means "keep it" — only a non-empty field replaces it.
  const keepStoredPassword = value.basicAuthPasswordSet && !password

  const handleConfirm = () => {
    if (enabled) {
      if (!username.trim()) {
        setError(t("apps.service.basicAuth.usernameRequired"))
        return
      }
      if (!password && !value.basicAuthPasswordSet) {
        setError(t("apps.service.basicAuth.passwordRequired"))
        return
      }
    }

    onConfirm({
      basicAuthEnabled: enabled,
      basicAuthUsername: enabled ? username.trim() : "",
      basicAuthPassword: enabled ? password : "",
      basicAuthPasswordSet: enabled && (value.basicAuthPasswordSet || !!password),
    })
    onOpenChange(false)
  }

  return (
    <Dialog open onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>{t("apps.service.basicAuth.title")}</DialogTitle>
          <DialogDescription>
            {t("apps.service.basicAuth.desc").replace("{host}", host || "-")}
          </DialogDescription>
        </DialogHeader>

        <div className="flex flex-col gap-4">
          <div className="flex items-center justify-between rounded-md border p-3">
            <Label htmlFor="basic-auth-enabled" className="cursor-pointer">
              {t("apps.service.basicAuth.enable")}
            </Label>
            <Switch
              id="basic-auth-enabled"
              checked={enabled}
              onCheckedChange={(checked) => {
                setEnabled(checked)
                setError(null)
              }}
            />
          </div>

          {enabled && (
            <>
              <div className="grid gap-2">
                <Label htmlFor="basic-auth-username">{t("apps.service.basicAuth.username")}</Label>
                <Input
                  id="basic-auth-username"
                  value={username}
                  autoComplete="off"
                  onChange={(event) => {
                    setUsername(event.target.value)
                    setError(null)
                  }}
                  placeholder={t("apps.service.basicAuth.usernamePlaceholder")}
                />
              </div>

              <div className="grid gap-2">
                <Label htmlFor="basic-auth-password">{t("apps.service.basicAuth.password")}</Label>
                <Input
                  id="basic-auth-password"
                  type="password"
                  value={password}
                  autoComplete="new-password"
                  onChange={(event) => {
                    setPassword(event.target.value)
                    setError(null)
                  }}
                  placeholder={
                    keepStoredPassword
                      ? t("apps.service.basicAuth.passwordKeep")
                      : t("apps.service.basicAuth.passwordPlaceholder")
                  }
                />
                {keepStoredPassword && (
                  <p className="text-xs text-muted-foreground">{t("apps.service.basicAuth.passwordKeepHint")}</p>
                )}
              </div>
            </>
          )}

          <p className="text-xs text-muted-foreground">{t("apps.service.basicAuth.republishNotice")}</p>
          {error && <p className="text-xs text-destructive">{error}</p>}
        </div>

        <DialogFooter>
          <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
            {t("common.cancel")}
          </Button>
          <Button type="button" onClick={handleConfirm}>
            {t("common.confirm")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
