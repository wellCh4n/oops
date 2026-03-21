"use client"

import { useEffect, useState } from "react"
import { toast } from "sonner"
import { fetchEnvironments } from "@/lib/api/environments"
import { fetchNodes } from "@/lib/api/nodes"
import { Environment, NodeStatus } from "@/lib/api/types"
import { Badge } from "@/components/ui/badge"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"

export default function NodesPage() {
  const [environments, setEnvironments] = useState<Environment[]>([])
  const [selectedEnv, setSelectedEnv] = useState("")
  const [nodes, setNodes] = useState<NodeStatus[]>([])
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    void (async () => {
      try {
        const res = await fetchEnvironments()
        const envs = res.data ?? []
        setEnvironments(envs)
        if (envs.length > 0) {
          setSelectedEnv(envs[0].name)
        }
      } catch {
        toast.error("获取环境列表失败")
      }
    })()
  }, [])

  useEffect(() => {
    if (!selectedEnv) return
    void (async () => {
      setLoading(true)
      try {
        const res = await fetchNodes(selectedEnv)
        setNodes(res.data ?? [])
      } catch {
        toast.error("获取节点状态失败")
        setNodes([])
      } finally {
        setLoading(false)
      }
    })()
  }, [selectedEnv])

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h2 className="text-2xl font-bold tracking-tight">节点</h2>
        <div className="flex items-center gap-2">
          <span className="text-sm font-medium whitespace-nowrap">环境:</span>
          <Select value={selectedEnv} onValueChange={setSelectedEnv}>
            <SelectTrigger className="w-[220px]">
              <SelectValue placeholder="选择环境" />
            </SelectTrigger>
            <SelectContent>
              {environments.map((env) => (
                <SelectItem key={env.id} value={env.name}>
                  {env.name}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
      </div>

      <div className="border rounded-md">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>节点</TableHead>
              <TableHead>状态</TableHead>
              <TableHead>角色</TableHead>
              <TableHead>IP</TableHead>
              <TableHead>CPU</TableHead>
              <TableHead>内存</TableHead>
              <TableHead>Pods</TableHead>
              <TableHead>版本</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {loading ? (
              <TableRow>
                <TableCell colSpan={8} className="py-2 text-center text-muted-foreground">
                  加载中...
                </TableCell>
              </TableRow>
            ) : nodes.length === 0 ? (
              <TableRow>
                <TableCell colSpan={8} className="h-24 text-center text-muted-foreground">
                  暂无数据
                </TableCell>
              </TableRow>
            ) : (
              nodes.map((node) => (
                <TableRow key={node.name}>
                  <TableCell className="font-medium">{node.name}</TableCell>
                  <TableCell>
                    <Badge variant={node.ready ? "default" : "destructive"}>
                      {node.ready ? "Ready" : "NotReady"}
                    </Badge>
                  </TableCell>
                  <TableCell>{node.roles || "-"}</TableCell>
                  <TableCell>{node.internalIP || "-"}</TableCell>
                  <TableCell>{node.cpu || "-"}</TableCell>
                  <TableCell>{node.memory || "-"}</TableCell>
                  <TableCell>{node.pods || "-"}</TableCell>
                  <TableCell>{node.kubeletVersion || "-"}</TableCell>
                </TableRow>
              ))
            )}
          </TableBody>
        </Table>
      </div>
    </div>
  )
}

