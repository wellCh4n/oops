"use client"

import { useState, useEffect } from "react"
import { Eye, EyeOff, Languages, Sun, Moon, Monitor } from "lucide-react"
import { useRouter } from "next/navigation"
import { login, getFeishuLoginUrl } from "@/lib/api/auth"
import { useFeaturesStore } from "@/store/features"
import { useTheme } from "next-themes"
import Image from "next/image"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { useLanguage } from "@/contexts/language-context"
import { localeLabels, Locale } from "@/lib/i18n"
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
} from "@/components/ui/dropdown-menu"

export default function LoginPage() {
  const router = useRouter()
  const { t, locale, setLocale } = useLanguage()
  const [username, setUsername] = useState("")
  const [password, setPassword] = useState("")
  const [error, setError] = useState("")
  const [loading, setLoading] = useState(false)
  const [showPassword, setShowPassword] = useState(false)
  const { features, load } = useFeaturesStore()
  const { theme, setTheme } = useTheme()

  useEffect(() => {
    load()
  }, [load])

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setError("")
    setLoading(true)
    try {
      await login(username, password)
      router.replace("/")
    } catch (err) {
      setError(err instanceof Error ? err.message : t("login.error"))
    } finally {
      setLoading(false)
    }
  }

  function handleFeishuLogin() {
    getFeishuLoginUrl().then((url) => {
      window.location.href = url
    })
  }

  return (
    <div className="min-h-screen flex items-center justify-center bg-background">
      <Card className="w-full max-w-sm">
        <CardHeader className="text-center">
          <div className="flex items-center justify-between mb-1">
            <div className="flex items-center gap-3">
              <div className="relative aspect-square size-10 overflow-hidden rounded-lg shrink-0">
                <Image src="/icon.png" alt="Oops" fill className="object-cover" />
              </div>
              <CardTitle className="text-2xl font-bold">OOPS</CardTitle>
            </div>
            <div className="flex items-center gap-1">
              <DropdownMenu>
                <DropdownMenuTrigger asChild>
                  <Button variant="ghost" size="sm" className="text-muted-foreground px-2">
                    {theme === "dark" ? <Moon className="h-4 w-4" /> : theme === "light" ? <Sun className="h-4 w-4" /> : <Monitor className="h-4 w-4" />}
                  </Button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end">
                  <DropdownMenuRadioGroup value={theme ?? "system"} onValueChange={setTheme}>
                    <DropdownMenuRadioItem value="system"><Monitor className="h-4 w-4" />{t("sidebar.themeSystem")}</DropdownMenuRadioItem>
                    <DropdownMenuRadioItem value="light"><Sun className="h-4 w-4" />{t("sidebar.themeLight")}</DropdownMenuRadioItem>
                    <DropdownMenuRadioItem value="dark"><Moon className="h-4 w-4" />{t("sidebar.themeDark")}</DropdownMenuRadioItem>
                  </DropdownMenuRadioGroup>
                </DropdownMenuContent>
              </DropdownMenu>
              <DropdownMenu>
                <DropdownMenuTrigger asChild>
                  <Button variant="ghost" size="sm" className="gap-1.5 text-muted-foreground">
                    <Languages className="h-4 w-4" />
                    {localeLabels[locale]}
                  </Button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end">
                  {(Object.keys(localeLabels) as Locale[]).map((l) => (
                    <DropdownMenuItem key={l} onClick={() => setLocale(l)} className={locale === l ? "font-medium" : ""}>
                      {localeLabels[l]}
                    </DropdownMenuItem>
                  ))}
                </DropdownMenuContent>
              </DropdownMenu>
            </div>
          </div>
          <p className="text-sm text-muted-foreground text-left"><span className="font-semibold text-primary">Kubernetes</span> Is All You Need</p>
        </CardHeader>
        <CardContent>
          <form onSubmit={handleSubmit} className="space-y-4">
            <div className="space-y-2">
              <Label htmlFor="username">{t("login.username")}</Label>
              <Input
                id="username"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                placeholder={t("login.usernamePlaceholder")}
                required
                autoFocus
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="password">{t("login.password")}</Label>
              <div className="relative">
                <Input
                  id="password"
                  type={showPassword ? "text" : "password"}
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  placeholder={t("login.passwordPlaceholder")}
                  required
                  className="pr-9"
                />
                <button
                  type="button"
                  onClick={() => setShowPassword(!showPassword)}
                  className="absolute right-2 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground cursor-pointer"
                  tabIndex={-1}
                >
                  {showPassword ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                </button>
              </div>
            </div>
            {error && (
              <p className="text-sm text-destructive">{error}</p>
            )}
            <Button type="submit" className="w-full" disabled={loading}>
              {loading ? t("login.loading") : t("login.submit")}
            </Button>
            {features.feishu && (
              <>
                <div className="relative">
                  <div className="absolute inset-0 flex items-center">
                    <span className="w-full border-t" />
                  </div>
                  <div className="relative flex justify-center text-xs uppercase">
                    <span className="bg-background px-2 text-muted-foreground">{t("login.or")}</span>
                  </div>
                </div>
                <Button
                  type="button"
                  variant="outline"
                  className="w-full"
                  onClick={handleFeishuLogin}
                >
                  <svg viewBox="0 0 48 48" className="size-5" fill="none" xmlns="http://www.w3.org/2000/svg">
                    <path d="M10 8c0 1 7 3.5 14.745 16.744 0 0 4.184-4.363 6.255-5.744 1.5-1 2.712-1.332 2.712-1.332C33.712 15.156 29.5 8 28 8z" fill="#00d6b9"/>
                    <path d="M43.5 18.5c-1-.667-3.65-1.771-6.5-1.5a15 15 0 0 0-3.288.668S32.5 18 31 19c-2.07 1.38-6.255 5.744-6.255 5.744-1.428 1.397-3.05 2.732-5.245 3.756 0 0 7 3 11.5 3 5.063 0 7-3.5 7-3.5 1.5-3.305 3.5-7 5.5-9.5" fill="#163c9a"/>
                    <path d="M4 17.5v17c0 1 6 5.5 15 5.5 10 0 17.05-7.705 19-12 0 0-1.937 3.5-7 3.5-4.5 0-11.5-3-11.5-3-5.117-2.239-10.03-6.577-12.906-9.117C4.974 17.953 4 17.093 4 17.5" fill="#3370ff"/>
                  </svg>
                  {t("login.feishu")}
                </Button>
              </>
            )}
          </form>
        </CardContent>
      </Card>
    </div>
  )
}
