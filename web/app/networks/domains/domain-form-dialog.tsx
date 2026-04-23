"use client"

import { useEffect, useRef, useState } from "react"
import { Upload } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Switch } from "@/components/ui/switch"
import { Textarea } from "@/components/ui/textarea"
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group"
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { toast } from "sonner"
import { Domain, DomainCertMode, DomainRequest, createDomain, updateDomain } from "@/lib/api/domains"
import { useLanguage } from "@/contexts/language-context"

interface Props {
  open: boolean
  onOpenChange: (open: boolean) => void
  target: Domain | null
  onSaved: () => void
}

export function DomainFormDialog({ open, onOpenChange, target, onSaved }: Props) {
  const { t } = useLanguage()
  const isEdit = !!target

  const [host, setHost] = useState("")
  const [description, setDescription] = useState("")
  const [https, setHttps] = useState(true)
  const [certMode, setCertMode] = useState<DomainCertMode>("AUTO")
  const [certPem, setCertPem] = useState("")
  const [keyPem, setKeyPem] = useState("")
  const [replaceCert, setReplaceCert] = useState(false)
  const [submitting, setSubmitting] = useState(false)

  useEffect(() => {
    if (!open) return
    if (target) {
      setHost(target.host)
      setDescription(target.description ?? "")
      setHttps(target.https)
      setCertMode(target.certMode ?? "AUTO")
      setCertPem("")
      setKeyPem("")
      setReplaceCert(false)
    } else {
      setHost("")
      setDescription("")
      setHttps(true)
      setCertMode("AUTO")
      setCertPem("")
      setKeyPem("")
      setReplaceCert(false)
    }
  }, [open, target])

  const showUploadFields = https && certMode === "UPLOADED" && (!isEdit || !target?.hasUploadedCert || replaceCert)
  const showKeepCertNotice = isEdit && https && certMode === "UPLOADED" && !!target?.hasUploadedCert && !replaceCert

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setSubmitting(true)
    try {
      const request: DomainRequest = {
        host: host.trim(),
        description: description.trim() || undefined,
        https,
      }
      if (https) {
        request.certMode = certMode
        if (certMode === "UPLOADED" && showUploadFields) {
          request.certPem = certPem
          request.keyPem = keyPem
        }
      }
      if (isEdit && target) {
        await updateDomain(target.id, request)
        toast.success(t("domains.updateSuccess"))
      } else {
        await createDomain(request)
        toast.success(t("domains.createSuccess"))
      }
      onSaved()
      onOpenChange(false)
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "Error")
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-xl max-h-[90vh] flex flex-col p-0 gap-0">
        <DialogHeader className="px-6 pt-6 pb-4">
          <DialogTitle>{isEdit ? t("domains.editTitle") : t("domains.createTitle")}</DialogTitle>
        </DialogHeader>
        <form onSubmit={handleSubmit} className="flex flex-col min-h-0 flex-1">
          <div className="space-y-4 overflow-y-auto px-6 pb-4">
          <div className="space-y-2">
            <Label htmlFor="domain-host">{t("domains.field.host")}</Label>
            <Input
              id="domain-host"
              value={host}
              onChange={(e) => setHost(e.target.value)}
              placeholder={t("domains.field.hostPlaceholder")}
              autoComplete="off"
              required
            />
          </div>

          <div className="space-y-2">
            <Label htmlFor="domain-description">{t("common.description")}</Label>
            <Input
              id="domain-description"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder={t("domains.field.descriptionPlaceholder")}
              autoComplete="off"
            />
          </div>

          <div className="flex items-center justify-between rounded-md border p-3">
            <Label htmlFor="domain-https" className="cursor-pointer">
              {t("domains.field.https")}
            </Label>
            <Switch id="domain-https" checked={https} onCheckedChange={setHttps} />
          </div>

          {https && (
            <div className="space-y-2">
              <Label>{t("domains.field.certMode")}</Label>
              <RadioGroup value={certMode} onValueChange={(v) => setCertMode(v as DomainCertMode)}>
                <div className="flex items-start gap-2">
                  <RadioGroupItem value="AUTO" id="mode-auto" className="mt-1" />
                  <Label htmlFor="mode-auto" className="cursor-pointer font-normal leading-relaxed">
                    {t("domains.field.certMode.auto")}
                  </Label>
                </div>
                <div className="flex items-start gap-2">
                  <RadioGroupItem value="UPLOADED" id="mode-uploaded" className="mt-1" />
                  <Label htmlFor="mode-uploaded" className="cursor-pointer font-normal leading-relaxed">
                    {t("domains.field.certMode.uploaded")}
                  </Label>
                </div>
              </RadioGroup>
            </div>
          )}

          {showKeepCertNotice && (
            <div className="rounded-md border bg-muted/30 p-3 text-sm space-y-1">
              <div className="font-medium">{t("domains.cert.uploaded")}</div>
              {target?.certSubject && (
                <div className="text-muted-foreground font-mono text-xs break-all">{target.certSubject}</div>
              )}
              {target?.certNotAfter && (
                <div className="text-muted-foreground">
                  {t("domains.cert.validUntil")}: {new Date(target.certNotAfter).toLocaleString()}
                </div>
              )}
              <Button
                type="button"
                variant="outline"
                size="sm"
                className="mt-2"
                onClick={() => setReplaceCert(true)}
              >
                {t("domains.cert.replace")}
              </Button>
            </div>
          )}

          {showUploadFields && (
            <>
              <PemField
                id="domain-cert"
                label={t("domains.field.certPem")}
                placeholder={t("domains.field.certPemPlaceholder")}
                uploadLabel={t("domains.field.uploadFile")}
                value={certPem}
                onChange={setCertPem}
              />
              <PemField
                id="domain-key"
                label={t("domains.field.keyPem")}
                placeholder={t("domains.field.keyPemPlaceholder")}
                uploadLabel={t("domains.field.uploadFile")}
                value={keyPem}
                onChange={setKeyPem}
              />
              {isEdit && target?.hasUploadedCert && (
                <Button type="button" variant="ghost" size="sm" onClick={() => setReplaceCert(false)}>
                  {t("domains.cert.keepExisting")}
                </Button>
              )}
            </>
          )}
          </div>

          <DialogFooter className="border-t px-6 py-4">
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
              {t("common.cancel")}
            </Button>
            <Button type="submit" disabled={submitting}>
              {submitting ? t("common.saving") : t("common.save")}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

interface PemFieldProps {
  id: string
  label: string
  placeholder: string
  uploadLabel: string
  value: string
  onChange: (value: string) => void
}

function PemField({ id, label, placeholder, uploadLabel, value, onChange }: PemFieldProps) {
  const inputRef = useRef<HTMLInputElement>(null)

  async function handleFileChange(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0]
    if (!file) return
    const text = await file.text()
    onChange(text)
    e.target.value = ""
  }

  return (
    <div className="space-y-2">
      <div className="flex items-center justify-between">
        <Label htmlFor={id}>{label}</Label>
        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={() => inputRef.current?.click()}
        >
          <Upload className="h-4 w-4" />
          {uploadLabel}
        </Button>
        <input
          ref={inputRef}
          type="file"
          accept=".pem,.crt,.cer,.key,text/plain"
          className="hidden"
          onChange={handleFileChange}
        />
      </div>
      <Textarea
        id={id}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        rows={3}
        className="font-mono text-xs resize-none [field-sizing:fixed] h-20 overflow-y-auto"
        required
      />
    </div>
  )
}
