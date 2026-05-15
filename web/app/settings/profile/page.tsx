"use client"

import { useEffect, useState } from "react"
import { useForm } from "react-hook-form"
import { zodResolver } from "@hookform/resolvers/zod"
import { z } from "zod"
import { Copy, Eye, EyeOff, Info, KeyRound, Loader2, ShieldCheck } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Form, FormControl, FormField, FormItem, FormLabel, FormMessage } from "@/components/ui/form"
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import { ContentPage } from "@/components/content-page"
import { useLanguage } from "@/contexts/language-context"
import { getCurrentUser, CurrentUser } from "@/lib/api/auth"
import { changeMyPassword, resetMyAccessToken, updateMyProfile } from "@/lib/api/users"
import { toast } from "sonner"

type ProfileFormValues = {
  email: string
}

type PasswordFormValues = {
  oldPassword: string
  newPassword: string
  confirmPassword: string
}

export default function ProfilePage() {
  const { t } = useLanguage()
  const [user, setUser] = useState<CurrentUser | null>(null)
  const [loading, setLoading] = useState(true)
  const [savingProfile, setSavingProfile] = useState(false)
  const [savingPassword, setSavingPassword] = useState(false)
  const [showOld, setShowOld] = useState(false)
  const [showNew, setShowNew] = useState(false)
  const [showConfirm, setShowConfirm] = useState(false)
  const [showToken, setShowToken] = useState(false)
  const [resetTokenOpen, setResetTokenOpen] = useState(false)
  const [resettingToken, setResettingToken] = useState(false)

  const profileSchema = z.object({
    email: z
      .string()
      .trim()
      .email({ message: t("profile.emailInvalid") })
      .or(z.literal("")),
  })

  const passwordSchema = z
    .object({
      oldPassword: z.string().min(1, { message: t("validation.required") }),
      newPassword: z.string().min(1, { message: t("validation.required") }),
      confirmPassword: z.string().min(1, { message: t("validation.required") }),
    })
    .refine((value) => value.newPassword === value.confirmPassword, {
      path: ["confirmPassword"],
      message: t("profile.pwdMismatch"),
    })

  const profileForm = useForm<ProfileFormValues>({
    resolver: zodResolver(profileSchema),
    defaultValues: { email: "" },
  })

  const passwordForm = useForm<PasswordFormValues>({
    resolver: zodResolver(passwordSchema),
    defaultValues: { oldPassword: "", newPassword: "", confirmPassword: "" },
  })

  useEffect(() => {
    getCurrentUser()
      .then((current) => {
        setUser(current)
        if (current) {
          profileForm.reset({ email: current.email ?? "" })
        }
      })
      .finally(() => setLoading(false))
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  async function onSaveProfile(values: ProfileFormValues) {
    setSavingProfile(true)
    try {
      const nextEmail = values.email.trim() ? values.email.trim() : null
      await updateMyProfile(nextEmail)
      toast.success(t("profile.updateSuccess"))
      const refreshed = await getCurrentUser()
      setUser(refreshed)
      if (refreshed) {
        profileForm.reset({ email: refreshed.email ?? "" })
      }
    } catch (error) {
      toast.error(error instanceof Error ? error.message : t("profile.updateError"))
    } finally {
      setSavingProfile(false)
    }
  }

  async function onChangePassword(values: PasswordFormValues) {
    setSavingPassword(true)
    try {
      await changeMyPassword(values.oldPassword, values.newPassword)
      toast.success(t("profile.pwdChanged"))
      passwordForm.reset({ oldPassword: "", newPassword: "", confirmPassword: "" })
      setShowOld(false)
      setShowNew(false)
      setShowConfirm(false)
    } catch (error) {
      toast.error(error instanceof Error ? error.message : t("profile.changePwdError"))
    } finally {
      setSavingPassword(false)
    }
  }

  async function onResetAccessToken() {
    setResettingToken(true)
    try {
      const token = await resetMyAccessToken()
      setUser((prev) => (prev ? { ...prev, accessToken: token } : prev))
      setShowToken(true)
      toast.success(t("profile.accessToken.resetSuccess"))
    } catch (error) {
      toast.error(error instanceof Error ? error.message : t("profile.accessToken.resetError"))
    } finally {
      setResettingToken(false)
      setResetTokenOpen(false)
    }
  }

  async function onCopyAccessToken() {
    if (!user?.accessToken) return
    try {
      await navigator.clipboard.writeText(user.accessToken)
      toast.success(t("profile.accessToken.copied"))
    } catch {
      toast.error(t("profile.accessToken.resetError"))
    }
  }

  if (loading) {
    return (
      <ContentPage title={t("profile.title")}>
        <div className="flex justify-center items-center py-16">
          <Loader2 className="animate-spin size-6 text-muted-foreground" />
        </div>
      </ContentPage>
    )
  }

  return (
    <ContentPage title={t("profile.title")}>
      <div className="space-y-6">
        <Form {...profileForm}>
          <form onSubmit={profileForm.handleSubmit(onSaveProfile)} className="flex w-full flex-col gap-4">
            <div className="border rounded-lg overflow-hidden">
              <div className="flex items-center gap-2 px-4 py-3 bg-muted/50 border-b">
                <Info className="size-4 text-muted-foreground" />
                <span className="text-sm font-semibold">{t("profile.basicInfo")}</span>
              </div>
              <div className="flex flex-col gap-4 p-4">
                <div className="grid grid-cols-2 gap-4">
                  <FormItem>
                    <FormLabel>{t("profile.username")}</FormLabel>
                    <FormControl>
                      <Input value={user?.username ?? ""} disabled autoComplete="off" />
                    </FormControl>
                  </FormItem>
                  <FormItem>
                    <FormLabel>{t("profile.role")}</FormLabel>
                    <FormControl>
                      <Input
                        value={user?.role === "ADMIN" ? t("profile.role.admin") : t("profile.role.user")}
                        disabled
                        autoComplete="off"
                      />
                    </FormControl>
                  </FormItem>
                  <FormField
                    control={profileForm.control}
                    name="email"
                    render={({ field }) => (
                      <FormItem className="col-span-2">
                        <FormLabel>{t("profile.email")}</FormLabel>
                        <FormControl>
                          <Input type="email" placeholder="user@example.com" autoComplete="off" {...field} />
                        </FormControl>
                        <FormMessage />
                      </FormItem>
                    )}
                  />
                </div>
              </div>
            </div>
            <div className="flex">
              <Button type="submit" disabled={savingProfile}>
                {savingProfile && <Loader2 className="size-4 animate-spin" />}
                {t("common.save")}
              </Button>
            </div>
          </form>
        </Form>

        <Form {...passwordForm}>
          <form onSubmit={passwordForm.handleSubmit(onChangePassword)} className="flex w-full flex-col gap-4">
            <div className="border rounded-lg overflow-hidden">
              <div className="flex items-center gap-2 px-4 py-3 bg-muted/50 border-b">
                <KeyRound className="size-4 text-muted-foreground" />
                <span className="text-sm font-semibold">{t("profile.changePassword")}</span>
              </div>
              <div className="flex flex-col gap-4 p-4">
                <FormField
                  control={passwordForm.control}
                  name="oldPassword"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>{t("profile.oldPassword")}</FormLabel>
                      <FormControl>
                        <div className="relative">
                          <Input type={showOld ? "text" : "password"} className="pr-10" autoComplete="off" {...field} />
                          <Button
                            type="button"
                            variant="ghost"
                            size="icon"
                            className="absolute right-0 top-0 size-9 text-muted-foreground hover:bg-transparent"
                            onClick={() => setShowOld(!showOld)}
                            tabIndex={-1}
                          >
                            {showOld ? <EyeOff className="size-4" /> : <Eye className="size-4" />}
                          </Button>
                        </div>
                      </FormControl>
                      <FormMessage />
                    </FormItem>
                  )}
                />
                <FormField
                  control={passwordForm.control}
                  name="newPassword"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>{t("profile.newPassword")}</FormLabel>
                      <FormControl>
                        <div className="relative">
                          <Input type={showNew ? "text" : "password"} className="pr-10" autoComplete="off" {...field} />
                          <Button
                            type="button"
                            variant="ghost"
                            size="icon"
                            className="absolute right-0 top-0 size-9 text-muted-foreground hover:bg-transparent"
                            onClick={() => setShowNew(!showNew)}
                            tabIndex={-1}
                          >
                            {showNew ? <EyeOff className="size-4" /> : <Eye className="size-4" />}
                          </Button>
                        </div>
                      </FormControl>
                      <FormMessage />
                    </FormItem>
                  )}
                />
                <FormField
                  control={passwordForm.control}
                  name="confirmPassword"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>{t("profile.confirmPassword")}</FormLabel>
                      <FormControl>
                        <div className="relative">
                          <Input type={showConfirm ? "text" : "password"} className="pr-10" autoComplete="off" {...field} />
                          <Button
                            type="button"
                            variant="ghost"
                            size="icon"
                            className="absolute right-0 top-0 size-9 text-muted-foreground hover:bg-transparent"
                            onClick={() => setShowConfirm(!showConfirm)}
                            tabIndex={-1}
                          >
                            {showConfirm ? <EyeOff className="size-4" /> : <Eye className="size-4" />}
                          </Button>
                        </div>
                      </FormControl>
                      <FormMessage />
                    </FormItem>
                  )}
                />
              </div>
            </div>
            <div className="flex">
              <Button type="submit" disabled={savingPassword}>
                {savingPassword && <Loader2 className="size-4 animate-spin" />}
                {t("profile.confirmChangePwd")}
              </Button>
            </div>
          </form>
        </Form>

        <div className="border rounded-lg overflow-hidden">
          <div className="flex items-center gap-2 px-4 py-3 bg-muted/50 border-b">
            <ShieldCheck className="size-4 text-muted-foreground" />
            <span className="text-sm font-semibold">{t("profile.accessToken")}</span>
          </div>
          <div className="flex flex-col gap-3 p-4">
            <div className="flex items-center gap-2">
              <div className="relative flex-1">
                <Input
                  readOnly
                  value={user?.accessToken ?? ""}
                  type={showToken ? "text" : "password"}
                  placeholder={t("profile.accessToken.empty")}
                  className="pr-20 font-mono"
                  autoComplete="off"
                  onBlur={() => setShowToken(false)}
                />
                <div className="absolute right-0 top-0 flex">
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon"
                    className="size-9 text-muted-foreground hover:bg-transparent"
                    onClick={() => setShowToken((prev) => !prev)}
                    disabled={!user?.accessToken}
                    tabIndex={-1}
                  >
                    {showToken ? <EyeOff className="size-4" /> : <Eye className="size-4" />}
                  </Button>
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon"
                    className="size-9 text-muted-foreground hover:bg-transparent"
                    onClick={onCopyAccessToken}
                    disabled={!user?.accessToken}
                    tabIndex={-1}
                  >
                    <Copy className="size-4" />
                  </Button>
                </div>
              </div>
              <Button
                type="button"
                variant="outline"
                onClick={() => {
                  if (user?.accessToken) {
                    setResetTokenOpen(true)
                  } else {
                    onResetAccessToken()
                  }
                }}
                disabled={resettingToken}
              >
                {resettingToken && <Loader2 className="size-4 animate-spin" />}
                {user?.accessToken ? t("profile.accessToken.reset") : t("profile.accessToken.generate")}
              </Button>
            </div>
            <p className="text-xs text-muted-foreground">{t("profile.accessToken.hint")}</p>
          </div>
        </div>
      </div>

      <AlertDialog open={resetTokenOpen} onOpenChange={setResetTokenOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t("profile.accessToken.reset")}</AlertDialogTitle>
            <AlertDialogDescription>{t("profile.accessToken.confirmReset")}</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={resettingToken}>{t("sidebar.cancel")}</AlertDialogCancel>
            <AlertDialogAction onClick={onResetAccessToken} disabled={resettingToken}>
              {t("profile.accessToken.reset")}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </ContentPage>
  )
}
