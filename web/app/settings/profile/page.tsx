"use client"

import { useEffect, useState } from "react"
import { useForm } from "react-hook-form"
import { zodResolver } from "@hookform/resolvers/zod"
import { z } from "zod"
import { Eye, EyeOff, Info, KeyRound, Loader2 } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Form, FormControl, FormField, FormItem, FormLabel, FormMessage } from "@/components/ui/form"
import { ContentPage } from "@/components/content-page"
import { useLanguage } from "@/contexts/language-context"
import { getCurrentUser, CurrentUser } from "@/lib/api/auth"
import { changeMyPassword, updateMyProfile } from "@/lib/api/users"
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

  if (loading) {
    return (
      <ContentPage title={t("profile.title")}>
        <div className="flex justify-center items-center py-16">
          <Loader2 className="animate-spin h-6 w-6 text-muted-foreground" />
        </div>
      </ContentPage>
    )
  }

  return (
    <ContentPage title={t("profile.title")}>
      <div className="space-y-6 max-w-3xl">
        <Form {...profileForm}>
          <form onSubmit={profileForm.handleSubmit(onSaveProfile)} className="flex w-full flex-col gap-4">
            <div className="border rounded-lg overflow-hidden">
              <div className="flex items-center gap-2 px-4 py-3 bg-muted/50 border-b">
                <Info className="h-4 w-4 text-muted-foreground" />
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
                {savingProfile && <Loader2 className="h-4 w-4 animate-spin" />}
                {t("common.save")}
              </Button>
            </div>
          </form>
        </Form>

        <Form {...passwordForm}>
          <form onSubmit={passwordForm.handleSubmit(onChangePassword)} className="flex w-full flex-col gap-4">
            <div className="border rounded-lg overflow-hidden">
              <div className="flex items-center gap-2 px-4 py-3 bg-muted/50 border-b">
                <KeyRound className="h-4 w-4 text-muted-foreground" />
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
                            className="absolute right-0 top-0 h-9 w-9 text-muted-foreground hover:bg-transparent"
                            onClick={() => setShowOld(!showOld)}
                            tabIndex={-1}
                          >
                            {showOld ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
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
                            className="absolute right-0 top-0 h-9 w-9 text-muted-foreground hover:bg-transparent"
                            onClick={() => setShowNew(!showNew)}
                            tabIndex={-1}
                          >
                            {showNew ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
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
                            className="absolute right-0 top-0 h-9 w-9 text-muted-foreground hover:bg-transparent"
                            onClick={() => setShowConfirm(!showConfirm)}
                            tabIndex={-1}
                          >
                            {showConfirm ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
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
                {savingPassword && <Loader2 className="h-4 w-4 animate-spin" />}
                {t("profile.confirmChangePwd")}
              </Button>
            </div>
          </form>
        </Form>
      </div>
    </ContentPage>
  )
}
