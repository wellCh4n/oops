import zhCN from "@/locales/zh-CN"
import zhTW from "@/locales/zh-TW"
import enUS from "@/locales/en-US"
import jaJP from "@/locales/ja-JP"

export type Locale = "zh-CN" | "zh-TW" | "en-US" | "ja-JP"

export const defaultLocale: Locale = "zh-CN"

export const localeLabels: Record<Locale, string> = {
  "zh-CN": "简体中文",
  "zh-TW": "繁體中文",
  "en-US": "English",
  "ja-JP": "日本語",
}

export const translations: Record<Locale, Record<string, string>> = {
  "zh-CN": zhCN,
  "zh-TW": zhTW,
  "en-US": enUS,
  "ja-JP": jaJP,
}
