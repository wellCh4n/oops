"use client"

import { useEffect, useState } from "react"
import { Plus, Eye, EyeOff, Search } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogTrigger } from "@/components/ui/dialog"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle } from "@/components/ui/alert-dialog"
import { DataTable } from "@/components/ui/data-table"
import { toast } from "sonner"
import { apiFetch } from "@/lib/api/client"
import { isAdmin } from "@/lib/auth"
import { columns, User } from "./columns"
import { ContentPage } from "@/components/content-page"
import { TableForm } from "@/components/ui/table-form"

export default function UsersPage() {
  const [users, setUsers] = useState<User[]>([])
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
  const [editLoading, setEditLoading] = useState(false)
  const [pwdTarget, setPwdTarget] = useState<User | null>(null)
  const [pwdNew, setPwdNew] = useState("")
  const [pwdConfirm, setPwdConfirm] = useState("")
  const [pwdShow, setPwdShow] = useState(false)
  const [pwdConfirmShow, setPwdConfirmShow] = useState(false)
  const [pwdLoading, setPwdLoading] = useState(false)

  useEffect(() => {
    setAdmin(isAdmin())
  }, [])

  async function loadUsers() {
    setTableLoading(true)
    try {
      const res = await apiFetch("/api/users")
      const data = await res.json()
      if (data.success) setUsers(data.data)
    } catch {
      toast.error("加载用户列表失败")
    } finally {
      setTableLoading(false)
    }
  }

  useEffect(() => {
    loadUsers()
  }, [])

  async function handleCreate(e: React.FormEvent) {
    e.preventDefault()
    if (password !== confirmPassword) {
      toast.error("两次输入的密码不一致")
      return
    }
    setLoading(true)
    try {
      const res = await apiFetch("/api/users", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ username, email: email || null, password }),
      })
      const data = await res.json()
      if (data.success) {
        toast.success("用户创建成功")
        setOpen(false)
        setUsername("")
        setEmail("")
        setPassword("")
        setConfirmPassword("")
        loadUsers()
      } else {
        toast.error(data.message || "创建失败")
      }
    } catch {
      toast.error("创建失败")
    } finally {
      setLoading(false)
    }
  }

  function handleEdit(user: User) {
    setEditTarget(user)
    setEditRole(user.role)
    setEditEmail(user.email || "")
  }

  async function confirmEdit() {
    if (!editTarget) return
    setEditLoading(true)
    try {
      const res = await apiFetch(`/api/users/${editTarget.id}`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ role: editRole, email: editEmail || null }),
      })
      const data = await res.json()
      if (data.success) {
        toast.success("用户已更新")
        setEditTarget(null)
        loadUsers()
      } else {
        toast.error(data.message || "更新失败")
      }
    } catch {
      toast.error("更新失败")
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
      toast.error("两次输入的密码不一致")
      return
    }
    if (!pwdTarget) return
    setPwdLoading(true)
    try {
      const res = await apiFetch(`/api/users/${pwdTarget.id}`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ role: pwdTarget.role, email: pwdTarget.email || null, password: pwdNew }),
      })
      const data = await res.json()
      if (data.success) {
        toast.success("密码已修改")
        setPwdTarget(null)
      } else {
        toast.error(data.message || "修改失败")
      }
    } catch {
      toast.error("修改失败")
    } finally {
      setPwdLoading(false)
    }
  }

  async function handleDelete(user: User) {
    setDeleteTarget(user)
  }

  async function confirmDelete() {
    if (!deleteTarget) return
    try {
      const res = await apiFetch(`/api/users/${deleteTarget.id}`, { method: "DELETE" })
      const data = await res.json()
      if (data.success) {
        toast.success("用户已删除")
        loadUsers()
      } else {
        toast.error(data.message || "删除失败")
      }
    } catch {
      toast.error("删除失败")
    } finally {
      setDeleteTarget(null)
    }
  }

  return (
    <ContentPage title="用户">
      <TableForm
        options={
          <div className="flex items-center justify-between gap-4">
            <div className="flex items-center gap-2">
              <span className="text-sm font-medium whitespace-nowrap">用户名或邮箱:</span>
              <div className="flex items-center space-x-2">
                <Input
                  value={search}
                  onChange={(e) => setSearch(e.target.value)}
                  onKeyDown={(e) => { if (e.key === "Enter") setAppliedSearch(search) }}
                  placeholder="搜索用户名或邮箱..."
                  className="w-56"
                />
                <Button variant="outline" onClick={() => setAppliedSearch(search)}>
                  <Search className="mr-2 h-4 w-4" />
                  搜索
                </Button>
              </div>
            </div>
            {admin && (
              <Dialog open={open} onOpenChange={(v) => { setOpen(v); if (!v) { setUsername(""); setEmail(""); setPassword(""); setConfirmPassword(""); setShowPassword(false); setShowConfirm(false) } }}>
                <DialogTrigger asChild>
                  <Button>
                    <Plus className="mr-2 h-4 w-4" />
                    创建用户
                  </Button>
                </DialogTrigger>
                <DialogContent>
                  <DialogHeader>
                    <DialogTitle>创建用户</DialogTitle>
                  </DialogHeader>
                  <form onSubmit={handleCreate} className="space-y-4">
                    <div className="space-y-2">
                      <Label htmlFor="new-username">用户名</Label>
                      <Input
                        id="new-username"
                        value={username}
                        onChange={(e) => setUsername(e.target.value)}
                        required
                      />
                    </div>
                    <div className="space-y-2">
                      <Label htmlFor="new-email">邮箱（可选）</Label>
                      <Input
                        id="new-email"
                        type="email"
                        value={email}
                        onChange={(e) => setEmail(e.target.value)}
                        placeholder="user@example.com"
                      />
                    </div>
                    <div className="space-y-2">
                      <Label htmlFor="new-password">密码</Label>
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
                          {showPassword ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                        </button>
                      </div>
                    </div>
                    <div className="space-y-2">
                      <Label htmlFor="confirm-password">确认密码</Label>
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
                          {showConfirm ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                        </button>
                      </div>
                    </div>
                    <div className="flex justify-end gap-2">
                      <Button type="button" variant="outline" onClick={() => setOpen(false)}>
                        取消
                      </Button>
                      <Button type="submit" disabled={loading}>
                        {loading ? "创建中..." : "创建"}
                      </Button>
                    </div>
                  </form>
                </DialogContent>
              </Dialog>
            )}
          </div>
        }
        table={
          <DataTable
            columns={columns}
            data={appliedSearch ? users.filter(u =>
              u.username.toLowerCase().includes(appliedSearch.toLowerCase()) ||
              (u.email ?? "").toLowerCase().includes(appliedSearch.toLowerCase())
            ) : users}
            loading={tableLoading}
            meta={{ onEdit: handleEdit, onChangePassword: handleChangePassword, onDelete: handleDelete, isAdmin: admin }}
          />
        }
      />

      <Dialog open={!!editTarget} onOpenChange={(v) => { if (!v) setEditTarget(null) }}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>编辑用户</DialogTitle>
          </DialogHeader>
          <div className="space-y-4">
            <div className="space-y-2">
              <Label>用户名</Label>
              <p className="text-sm font-mono">{editTarget?.username}</p>
            </div>
            <div className="space-y-2">
              <Label htmlFor="edit-email">邮箱</Label>
              <Input
                id="edit-email"
                type="email"
                value={editEmail}
                onChange={(e) => setEditEmail(e.target.value)}
                placeholder="user@example.com"
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="edit-role">角色</Label>
              <Select value={editRole} onValueChange={setEditRole}>
                <SelectTrigger id="edit-role">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="USER">普通用户</SelectItem>
                  <SelectItem value="ADMIN">管理员</SelectItem>
                </SelectContent>
              </Select>
            </div>
            <div className="flex justify-end gap-2">
              <Button variant="outline" onClick={() => setEditTarget(null)}>取消</Button>
              <Button onClick={confirmEdit} disabled={editLoading}>
                {editLoading ? "保存中..." : "保存"}
              </Button>
            </div>
          </div>
        </DialogContent>
      </Dialog>

      <Dialog open={!!pwdTarget} onOpenChange={(v) => { if (!v) setPwdTarget(null) }}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>修改密码</DialogTitle>
          </DialogHeader>
          <div className="space-y-4">
            <div className="space-y-2">
              <Label>用户名</Label>
              <p className="text-sm font-mono">{pwdTarget?.username}</p>
            </div>
            <div className="space-y-2">
              <Label htmlFor="pwd-new">新密码</Label>
              <div className="relative">
                <Input
                  id="pwd-new"
                  type={pwdShow ? "text" : "password"}
                  value={pwdNew}
                  onChange={(e) => setPwdNew(e.target.value)}
                  className="pr-9"
                />
                <button type="button" onClick={() => setPwdShow(!pwdShow)} className="absolute right-2 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground cursor-pointer" tabIndex={-1}>
                  {pwdShow ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                </button>
              </div>
            </div>
            <div className="space-y-2">
              <Label htmlFor="pwd-confirm">确认密码</Label>
              <div className="relative">
                <Input
                  id="pwd-confirm"
                  type={pwdConfirmShow ? "text" : "password"}
                  value={pwdConfirm}
                  onChange={(e) => setPwdConfirm(e.target.value)}
                  className="pr-9"
                />
                <button type="button" onClick={() => setPwdConfirmShow(!pwdConfirmShow)} className="absolute right-2 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground cursor-pointer" tabIndex={-1}>
                  {pwdConfirmShow ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                </button>
              </div>
            </div>
            <div className="flex justify-end gap-2">
              <Button variant="outline" onClick={() => setPwdTarget(null)}>取消</Button>
              <Button onClick={confirmChangePassword} disabled={pwdLoading || !pwdNew}>
                {pwdLoading ? "修改中..." : "确认修改"}
              </Button>
            </div>
          </div>
        </DialogContent>
      </Dialog>

      <AlertDialog open={!!deleteTarget} onOpenChange={(v) => { if (!v) setDeleteTarget(null) }}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>确认删除用户</AlertDialogTitle>
            <AlertDialogDescription>
              确定要删除用户 <strong>{deleteTarget?.username}</strong> 吗？此操作不可撤销。
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction variant="destructive" onClick={confirmDelete}>确认删除</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </ContentPage>
  )
}
