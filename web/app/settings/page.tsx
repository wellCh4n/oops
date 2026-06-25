"use client"

import { useLanguage } from "@/contexts/language-context"

export default function SettingsPage() {
  const { t } = useLanguage()
  return (
    <div className="p-6">
      <h1 className="text-2xl font-semibold">{t("settings.title")}</h1>
      <p className="mt-4">{t("settings.selectSubmenu")}</p>
    </div>
  )
}
