"use client"

import { useEffect, useState, useCallback } from "react"
import { Plus, Eye, EyeOff, Search, Trash2, ChevronLeft, ChevronRight } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle, DialogTrigger } from "@/components/ui/dialog"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Switch } from "@/components/ui/switch"
import { AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle } from "@/components/ui/alert-dialog"
import { DataTable } from "@/components/ui/data-table"
import { toast } from "sonner"
import { isAdmin } from "@/lib/auth"
import { getColumns } from "./columns"
import { ContentPage } from "@/components/content-page"
import { TableForm } from "@/components/ui/table-form"
import { useLanguage } from "@/contexts/language-context"
import { fetchUsersPage, createUser, updateUser, deleteUser, User } from "@/lib/api/users"

export default function UsersPage() {
  const [users, setUsers] = useState<User[]>([])
  const [page, setPage] = useState(1)
  const [size, setSize] = useState(10)
  const [totalPages, setTotalPages] = useState(0)
  const [open, setOpen] = useState(false)
  const [username, setUsername] = useState("")
  const [email, setEmail] = useState("")
  const [password, setPassword] = useState("")
  const [confirmPassword, setConfirmPassword] = useState("")
  const [showPassword, setShowPassword] = useState(false)
  const [showConfirm, setShowConfirm] = useState(false)
  const [loading, setLoading] = useState(false)
  const [tableLoading, setTableLoading] = useState(true)
  const [admin, setAdmin] = useState(false)
  const [search, setSearch] = useState("")
  const [appliedSearch, setAppliedSearch] = useState("")
  const [deleteTarget, setDeleteTarget] = useState<User | null>(null)
  const [editTarget, setEditTarget] = useState<User | null>(null)
  const [editRole, setEditRole] = useState<string>("USER")
  const [editEmail, setEditEmail] = useState<string>("")
  const [editEnabled, setEditEnabled] = useState<boolean>(true)
  const [editLoading, setEditLoading] = useState(false)
  const [pwdTarget, setPwdTarget] = useState<User | null>(null)
  const [pwdNew, setPwdNew] = useState("")
  const [pwdConfirm, setPwdConfirm] = useState("")
  const [pwdShow, setPwdShow] = useState(false)
  const [pwdConfirmShow, setPwdConfirmShow] = useState(false)
  const [pwdLoading, setPwdLoading] = useState(false)
  const { t } = useLanguage()

  useEffect(() => {
    setAdmin(isAdmin())
  }, [])

  const loadUsers = useCallback(async () => {
    setTableLoading(true)
    try {
      const result = await fetchUsersPage(appliedSearch || undefined, page, size)
      setUsers(result.data)
      setTotalPages(result.totalPages)
    } catch {
      toast.error(t("users.fetchError"))
    } finally {
      setTableLoading(false)
    }
  }, [t, appliedSearch, page, size])

  useEffect(() => {
    loadUsers()
  }, [loadUsers])

  async function handleCreate(e: React.FormEvent) {
    e.preventDefault()
    if (password !== confirmPassword) {
      toast.error(t("users.pwdMismatch"))
      return
    }
    setLoading(true)
    try {
      await createUser({ username, email, password })
      toast.success(t("users.createSuccess"))
      setOpen(false)
      setUsername("")
      setEmail("")
      setPassword("")
      setConfirmPassword("")
      loadUsers()
    } catch (err) {
      toast.error(err instanceof Error ? err.message : t("users.createError"))
    } finally {
      setLoading(false)
    }
  }

  function handleEdit(user: User) {
    setEditTarget(user)
    setEditRole(user.role)
    setEditEmail(user.email || "")
    setEditEnabled(user.enabled ?? true)
  }

  async function confirmEdit() {
    if (!editTarget) return
    setEditLoading(true)
    try {
      await updateUser(editTarget.id, { role: editRole, email: editEmail || null, enabled: editEnabled })
      toast.success(t("users.updateSuccess"))
      setEditTarget(null)
      loadUsers()
    } catch (err) {
      toast.error(err instanceof Error ? err.message : t("users.updateError"))
    } finally {
      setEditLoading(false)
    }
  }

  function handleChangePassword(user: User) {
    setPwdTarget(user)
    setPwdNew("")
    setPwdConfirm("")
    setPwdShow(false)
    setPwdConfirmShow(false)
  }

  async function confirmChangePassword() {
    if (pwdNew !== pwdConfirm) {
      toast.error(t("users.pwdMismatch"))
      return
    }
    if (!pwdTarget) return
    setPwdLoading(true)
    try {
      await updateUser(pwdTarget.id, { role: pwdTarget.role, email: pwdTarget.email || null, password: pwdNew })
      toast.success(t("users.pwdChanged"))
      setPwdTarget(null)
    } catch (err) {
      toast.error(err instanceof Error ? err.message : t("users.changePwdError"))
    } finally {
      setPwdLoading(false)
    }
  }

  async function confirmDelete() {
    if (!deleteTarget) return
    try {
      await deleteUser(deleteTarget.id)
      toast.success(t("users.deleteSuccess"))
      setEditTarget(null)
      loadUsers()
    } catch (err) {
      toast.error(err instanceof Error ? err.message : t("users.deleteError"))
    } finally {
      setDeleteTarget(null)
    }
  }

  return (
    <ContentPage title={t("users.title")}>
      <TableForm
        options={
          <div className="flex items-end justify-between gap-4">
            <div className="flex flex-col gap-1.5">
              <span className="text-sm font-medium leading-none whitespace-nowrap flex items-center gap-1.5"><Search className="size-4" />{t("users.searchLabel")}</span>
              <div className="flex items-center gap-2">
                <Input
                  value={search}
                  onChange={(e) => setSearch(e.target.value)}
                  onKeyDown={(e) => { if (e.key === "Enter") { setPage(1); setAppliedSearch(search) } }}
                  placeholder={t("users.searchPlaceholder")}
                  className="w-56"
                />
                <Button variant="outline" onClick={() => { setPage(1); setAppliedSearch(search) }}>
                  <Search className="size-4" />
                  {t("common.search")}
                </Button>
              </div>
            </div>
            {admin && (
              <Dialog open={open} onOpenChange={(v) => { setOpen(v); if (!v) { setUsername(""); setEmail(""); setPassword(""); setConfirmPassword(""); setShowPassword(false); setShowConfirm(false) } }}>
                <DialogTrigger asChild>
                  <Button>
                    <Plus className="size-4" />
                    {t("users.createBtn")}
                  </Button>
                </DialogTrigger>
                <DialogContent>
                  <DialogHeader>
                    <DialogTitle>{t("users.createTitle")}</DialogTitle>
                    <DialogDescription>{t("users.createDesc")}</DialogDescription>
                  </DialogHeader>
                  <form onSubmit={handleCreate} className="space-y-4">
                    <div className="space-y-2">
                      <Label htmlFor="new-username">{t("users.col.username")}</Label>
                      <Input
                        id="new-username"
                        value={username}
                        onChange={(e) => setUsername(e.target.value)}
                        required
                      />
                    </div>
                    <div className="space-y-2">
                      <Label htmlFor="new-email">{t("users.email")}</Label>
                      <Input
                        id="new-email"
                        type="email"
                        value={email}
                        onChange={(e) => setEmail(e.target.value)}
                        required
                        placeholder="user@example.com"
                      />
                    </div>
                    <div className="space-y-2">
                      <Label htmlFor="new-password">{t("users.password")}</Label>
                      <div className="relative">
                        <Input
                          id="new-password"
                          type={showPassword ? "text" : "password"}
                          value={password}
                          onChange={(e) => setPassword(e.target.value)}
                          required
                          className="pr-9"
                        />
                        <button
                          type="button"
                          onClick={() => setShowPassword(!showPassword)}
                          className="absolute right-2 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground cursor-pointer"
                          tabIndex={-1}
                        >
                          {showPassword ? <EyeOff className="size-4" /> : <Eye className="size-4" />}
                        </button>
                      </div>
                    </div>
                    <div className="space-y-2">
                      <Label htmlFor="confirm-password">{t("users.confirmPassword")}</Label>
                      <div className="relative">
                        <Input
                          id="confirm-password"
                          type={showConfirm ? "text" : "password"}
                          value={confirmPassword}
                          onChange={(e) => setConfirmPassword(e.target.value)}
                          required
                          className="pr-9"
                        />
                        <button
                          type="button"
                          onClick={() => setShowConfirm(!showConfirm)}
                          className="absolute right-2 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground cursor-pointer"
                          tabIndex={-1}
                        >
                          {showConfirm ? <EyeOff className="size-4" /> : <Eye className="size-4" />}
                        </button>
                      </div>
                    </div>
                    <div className="flex justify-end gap-2">
                      <Button type="button" variant="outline" onClick={() => setOpen(false)}>
                        {t("common.cancel")}
                      </Button>
                      <Button type="submit" disabled={loading}>
                        {loading ? t("users.creating") : t("users.create")}
                      </Button>
                    </div>
                  </form>
                </DialogContent>
              </Dialog>
            )}
          </div>
        }
        table={
          <>
            <DataTable
              columns={getColumns(t)}
              data={users}
              loading={tableLoading}
              meta={{ onEdit: handleEdit, onChangePassword: handleChangePassword, isAdmin: admin }}
            />
            <div className="flex items-center justify-end gap-4 mt-2">
              <div className="flex items-center gap-2">
                <span className="text-sm text-muted-foreground">{t("common.pageSize")}</span>
                <Select
                  value={String(size)}
                  onValueChange={(v) => { setPage(1); setSize(Number(v)) }}
                  disabled={tableLoading}
                >
                  <SelectTrigger className="w-[70px] h-8">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="10">10</SelectItem>
                    <SelectItem value="20">20</SelectItem>
                    <SelectItem value="50">50</SelectItem>
                  </SelectContent>
                </Select>
                <span className="text-sm text-muted-foreground">{t("common.pageSizeSuffix")}</span>
              </div>
              <div className="flex items-center gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  disabled={page === 1 || tableLoading}
                  onClick={() => setPage(p => p - 1)}
                >
                  <ChevronLeft className="size-4" />
                  {t("common.prevPage")}
                </Button>
                <span className="text-sm text-muted-foreground">
                  {t("common.pagePrefix")}{page}{t("common.pageSuffix")} / {t("common.totalPages").replace("${total}", String(totalPages))}
                </span>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={page >= totalPages || tableLoading}
                  onClick={() => setPage(p => p + 1)}
                >
                  {t("common.nextPage")}
                  <ChevronRight className="ml-2 size-4" />
                </Button>
              </div>
            </div>
          </>
        }
      />

      <Dialog open={!!editTarget} onOpenChange={(v) => { if (!v) setEditTarget(null) }}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t("users.editTitle")}</DialogTitle>
            <DialogDescription>{t("users.editDesc")}</DialogDescription>
          </DialogHeader>
          <div className="space-y-4">
            <div className="space-y-2">
              <Label>{t("users.col.username")}</Label>
              <p className="text-sm font-mono">{editTarget?.username}</p>
            </div>
            <div className="space-y-2">
              <Label htmlFor="edit-email">{t("users.col.email")}</Label>
              <Input
                id="edit-email"
                type="email"
                value={editEmail}
                onChange={(e) => setEditEmail(e.target.value)}
                placeholder="user@example.com"
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="edit-role">{t("users.col.role")}</Label>
              <Select value={editRole} onValueChange={setEditRole}>
                <SelectTrigger id="edit-role">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="USER">{t("users.role.user")}</SelectItem>
                  <SelectItem value="ADMIN">{t("users.role.admin")}</SelectItem>
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-2">
              <Label htmlFor="edit-enabled">{t("users.col.status")}</Label>
              <div className="flex items-center gap-2">
                <Switch id="edit-enabled" checked={editEnabled} onCheckedChange={setEditEnabled} />
                <span className="text-sm text-muted-foreground">
                  {editEnabled ? t("users.status.enabled") : t("users.status.disabled")}
                </span>
              </div>
            </div>
            <div className="flex justify-end gap-2">
              {editTarget?.role !== "ADMIN" && (
                <Button
                  variant="destructive"
                  className="mr-auto"
                  onClick={() => setDeleteTarget(editTarget)}
                  disabled={editLoading}
                >
                  <Trash2 className="size-4" />
                  {t("users.col.delete")}
                </Button>
              )}
              <Button variant="outline" onClick={() => setEditTarget(null)}>{t("common.cancel")}</Button>
              <Button onClick={confirmEdit} disabled={editLoading}>
                {editLoading ? t("common.saving") : t("common.save")}
              </Button>
            </div>
          </div>
        </DialogContent>
      </Dialog>

      <Dialog open={!!pwdTarget} onOpenChange={(v) => { if (!v) setPwdTarget(null) }}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t("users.changePwdTitle")}</DialogTitle>
            <DialogDescription>{t("users.changePwdDesc")}</DialogDescription>
          </DialogHeader>
          <div className="space-y-4">
            <div className="space-y-2">
              <Label>{t("users.col.username")}</Label>
              <p className="text-sm font-mono">{pwdTarget?.username}</p>
            </div>
            <div className="space-y-2">
              <Label htmlFor="pwd-new">{t("users.newPassword")}</Label>
              <div className="relative">
                <Input
                  id="pwd-new"
                  type={pwdShow ? "text" : "password"}
                  value={pwdNew}
                  onChange={(e) => setPwdNew(e.target.value)}
                  className="pr-9"
                />
                <button type="button" onClick={() => setPwdShow(!pwdShow)} className="absolute right-2 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground cursor-pointer" tabIndex={-1}>
                  {pwdShow ? <EyeOff className="size-4" /> : <Eye className="size-4" />}
                </button>
              </div>
            </div>
            <div className="space-y-2">
              <Label htmlFor="pwd-confirm">{t("users.confirmPassword")}</Label>
              <div className="relative">
                <Input
                  id="pwd-confirm"
                  type={pwdConfirmShow ? "text" : "password"}
                  value={pwdConfirm}
                  onChange={(e) => setPwdConfirm(e.target.value)}
                  className="pr-9"
                />
                <button type="button" onClick={() => setPwdConfirmShow(!pwdConfirmShow)} className="absolute right-2 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground cursor-pointer" tabIndex={-1}>
                  {pwdConfirmShow ? <EyeOff className="size-4" /> : <Eye className="size-4" />}
                </button>
              </div>
            </div>
            <div className="flex justify-end gap-2">
              <Button variant="outline" onClick={() => setPwdTarget(null)}>{t("common.cancel")}</Button>
              <Button onClick={confirmChangePassword} disabled={pwdLoading || !pwdNew}>
                {pwdLoading ? t("users.changingPwd") : t("users.confirmChangePwd")}
              </Button>
            </div>
          </div>
        </DialogContent>
      </Dialog>

      <AlertDialog open={!!deleteTarget} onOpenChange={(v) => { if (!v) setDeleteTarget(null) }}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t("users.deleteTitle")}</AlertDialogTitle>
            <AlertDialogDescription>
              {t("users.deleteDescPrefix")}<strong>{deleteTarget?.username}</strong>{t("users.deleteDescSuffix")}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>{t("common.cancel")}</AlertDialogCancel>
            <AlertDialogAction variant="destructive" onClick={confirmDelete}>{t("users.confirmDelete")}</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </ContentPage>
  )
}
