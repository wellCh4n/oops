import zh from "@/locales/zh"
import en from "@/locales/en"

export type Locale = "zh" | "en"

export const defaultLocale: Locale = "zh"

export const localeLabels: Record<Locale, string> = {
  zh: "中文",
  en: "English",
}

export const translations: Record<Locale, Record<string, string>> = { zh, en }
