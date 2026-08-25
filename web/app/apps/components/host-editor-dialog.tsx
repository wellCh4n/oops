"use client"

import { useMemo, useState } from "react"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Switch } from "@/components/ui/switch"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Domain } from "@/lib/api/domains"
import { isValidHost } from "@/lib/host-validation"
import { useLanguage } from "@/contexts/language-context"

export interface HostEditorValue {
  host: string
  https: boolean
  basicAuthEnabled: boolean
  basicAuthUsername: string
  basicAuthPassword: string
  basicAuthPasswordSet: boolean
}

interface Props {
  /** Undefined host means "add". */
  value?: HostEditorValue
  domains: Domain[]
  onOpenChange: (open: boolean) => void
  /** Returns an error message to show, or null when the host is accepted. */
  checkHost: (host: string) => Promise<string | null>
  onConfirm: (value: HostEditorValue) => void
}

export function splitHost(fullHost: string, domains: Domain[]): { prefix: string; suffix: string } {
  const lower = fullHost.trim().toLowerCase()
  if (!lower) return { prefix: "", suffix: "" }

  const hosts = domains.map((domain) => domain.host).sort((a, b) => b.length - a.length)
  const matchHost = hosts.find((host) => lower === host || lower.endsWith("." + host))
  if (!matchHost) {
    return { prefix: lower, suffix: "" }
  }
  if (lower === matchHost) {
    return { prefix: "", suffix: matchHost }
  }

  return { prefix: lower.slice(0, -(matchHost.length + 1)), suffix: matchHost }
}

function combineHost(prefix: string, suffix: string) {
  const normalizedPrefix = prefix.trim().toLowerCase()
  if (!suffix) {
    return normalizedPrefix
  }

  return normalizedPrefix ? `${normalizedPrefix}.${suffix}` : suffix
}

/**
 * One form for everything about a host: the name, the scheme and basic auth. Mounted only while
 * open, so the fields simply start from the host's current values.
 */
export function HostEditorDialog({ value, domains, onOpenChange, checkHost, onConfirm }: Props) {
  const { t } = useLanguage()
  const isEdit = !!value

  const initial = useMemo(() => splitHost(value?.host ?? "", domains), [value, domains])
  const [prefix, setPrefix] = useState(initial.prefix)
  const [suffix, setSuffix] = useState(initial.suffix || (domains.length === 1 ? domains[0].host : ""))
  const [https, setHttps] = useState(value?.https ?? true)
  const [basicAuthEnabled, setBasicAuthEnabled] = useState(value?.basicAuthEnabled ?? false)
  const [username, setUsername] = useState(value?.basicAuthUsername ?? "")
  const [password, setPassword] = useState(value?.basicAuthPassword ?? "")
  const [error, setError] = useState<string | null>(null)
  const [checking, setChecking] = useState(false)

  const passwordSet = value?.basicAuthPasswordSet ?? false
  const keepStoredPassword = passwordSet && !password
  const combinedHost = combineHost(prefix, suffix)
  const isApex = !!suffix && !prefix.trim()

  const handleConfirm = async () => {
    if (!suffix) {
      setError(t("apps.service.suffixRequired"))
      return
    }
    if (!isValidHost(combinedHost)) {
      setError(t("apps.service.hostInvalid"))
      return
    }
    if (basicAuthEnabled) {
      if (!username.trim()) {
        setError(t("apps.service.basicAuth.usernameRequired"))
        return
      }
      if (!password && !passwordSet) {
        setError(t("apps.service.basicAuth.passwordRequired"))
        return
      }
    }

    setChecking(true)
    try {
      const hostError = await checkHost(combinedHost)
      if (hostError) {
        setError(hostError)
        return
      }
    } finally {
      setChecking(false)
    }

    onConfirm({
      host: combinedHost,
      https,
      basicAuthEnabled,
      basicAuthUsername: basicAuthEnabled ? username.trim() : "",
      basicAuthPassword: basicAuthEnabled ? password : "",
      basicAuthPasswordSet: basicAuthEnabled && (passwordSet || !!password),
    })
    onOpenChange(false)
  }

  return (
    <Dialog open onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>{isEdit ? t("apps.service.editHostTitle") : t("apps.service.addHost")}</DialogTitle>
          <DialogDescription>{t("apps.service.hostEditorDesc")}</DialogDescription>
        </DialogHeader>

        <div className="flex flex-col gap-4">
          <div className="grid gap-2">
            <Label htmlFor="host-prefix">{t("apps.service.hostName")}</Label>
            <div className="flex items-center gap-1">
              <Input
                id="host-prefix"
                className="flex-1"
                autoComplete="off"
                autoFocus
                value={prefix}
                onChange={(event) => {
                  setPrefix(event.target.value.trim().toLowerCase())
                  setError(null)
                }}
                placeholder={t("apps.service.prefixPlaceholder")}
              />
              <span className="text-muted-foreground">.</span>
              <Select
                value={suffix}
                onValueChange={(next) => {
                  setSuffix(next ?? "")
                  setError(null)
                }}
              >
                <SelectTrigger className="flex-1">
                  <SelectValue placeholder={t("apps.service.suffixPlaceholder")}>{suffix}</SelectValue>
                </SelectTrigger>
                <SelectContent>
                  {domains.map((domain) => (
                    <SelectItem key={domain.id} value={domain.host}>
                      <div className="flex flex-col items-start">
                        <span>{domain.host}</span>
                        {domain.description && (
                          <span className="text-xs text-muted-foreground">{domain.description}</span>
                        )}
                      </div>
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <p className={isApex ? "text-xs text-amber-600 dark:text-amber-500" : "text-xs text-muted-foreground"}>
              {suffix
                ? isApex
                  ? t("apps.service.apexNotice").replace("{host}", suffix)
                  : `${https ? "https" : "http"}://${combinedHost}`
                : t("apps.service.domainPlaceholder")}
            </p>
          </div>

          <div className="flex items-center justify-between rounded-md border p-3">
            <div className="grid gap-0.5">
              <Label htmlFor="host-https" className="cursor-pointer">HTTPS</Label>
              <span className="text-xs text-muted-foreground">{t("apps.service.httpsHint")}</span>
            </div>
            <Switch id="host-https" checked={https} onCheckedChange={setHttps} />
          </div>

          <div className="rounded-md border">
            <div className="flex items-center justify-between p-3">
              <div className="grid gap-0.5">
                <Label htmlFor="host-basic-auth" className="cursor-pointer">{t("apps.service.basicAuth.enable")}</Label>
                <span className="text-xs text-muted-foreground">{t("apps.service.basicAuth.hint")}</span>
              </div>
              <Switch
                id="host-basic-auth"
                checked={basicAuthEnabled}
                onCheckedChange={(checked) => {
                  setBasicAuthEnabled(checked)
                  setError(null)
                }}
              />
            </div>
            {basicAuthEnabled && (
              <div className="grid gap-4 border-t p-3">
                <div className="grid gap-2">
                  <Label htmlFor="host-basic-auth-username">{t("apps.service.basicAuth.username")}</Label>
                  <Input
                    id="host-basic-auth-username"
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
                  <Label htmlFor="host-basic-auth-password">{t("apps.service.basicAuth.password")}</Label>
                  <Input
                    id="host-basic-auth-password"
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
              </div>
            )}
          </div>

          <p className="text-xs text-muted-foreground">{t("apps.service.accessEntryRepublishNotice")}</p>
          {error && <p className="text-xs text-destructive">{error}</p>}
        </div>

        <DialogFooter>
          <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
            {t("common.cancel")}
          </Button>
          <Button type="button" onClick={handleConfirm} disabled={checking}>
            {t("common.confirm")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
