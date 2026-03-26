"use client"

import React, { createContext, useContext, useState } from "react"
import { Locale, defaultLocale, translations } from "@/lib/i18n"

interface LanguageContextType {
  locale: Locale
  setLocale: (locale: Locale) => void
  t: (key: string) => string
}

const LanguageContext = createContext<LanguageContextType>({
  locale: defaultLocale,
  setLocale: () => {},
  t: (key) => key,
})

export function LanguageProvider({
  children,
  initialLocale = defaultLocale,
}: {
  children: React.ReactNode
  initialLocale?: Locale
}) {
  const [locale, setLocaleState] = useState<Locale>(() => {
    return initialLocale
  })

  function setLocale(newLocale: Locale) {
    setLocaleState(newLocale)
    localStorage.setItem("locale", newLocale)
    document.cookie = `locale=${newLocale}; path=/; max-age=31536000; samesite=lax`
  }

  function t(key: string): string {
    return translations[locale][key] ?? key
  }

  return (
    <LanguageContext.Provider value={{ locale, setLocale, t }}>
      {children}
    </LanguageContext.Provider>
  )
}

export function useLanguage() {
  return useContext(LanguageContext)
}
