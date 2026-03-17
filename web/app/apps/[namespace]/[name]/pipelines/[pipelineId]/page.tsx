"use client"

import { use, useState, useEffect, useRef } from "react"
import { getPipeline } from "@/lib/api/pipelines"
import { Pipeline } from "@/lib/api/types"
import { API_BASE_URL } from "@/lib/api/config"
import { Badge } from "@/components/ui/badge"
import { toast } from "sonner"
import dayjs from "dayjs"

interface PageProps {
  params: Promise<{
    namespace: string
    name: string
    pipelineId: string
  }>
}

export default function PipelineDetailPage({ params }: PageProps) {
  const { namespace, name, pipelineId } = use(params)
  const [pipeline, setPipeline] = useState<Pipeline | null>(null)
  const [logs, setLogs] = useState<string[]>([])
  const [steps, setSteps] = useState<string[]>([])
  const [activeStep, setActiveStep] = useState<string>("")
  const logContainerRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const fetchPipeline = async () => {
      try {
        const res = await getPipeline(namespace, name, pipelineId)
        if (res.data) {
          setPipeline(res.data)
        }
      } catch (error) {
        toast.error("Failed to fetch pipeline details")
      }
    }
    fetchPipeline()
  }, [namespace, name, pipelineId])

  useEffect(() => {
    // Use WebSocket instead of SSE
    const wsProtocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    const baseUrl = API_BASE_URL.startsWith('http')
      ? API_BASE_URL.replace(/^http/, 'ws')
      : `${wsProtocol}//${window.location.host}${API_BASE_URL}`

    const wsUrl = `${baseUrl}/api/namespaces/${namespace}/applications/${name}/pipelines/${pipelineId}/log`
    let ws: WebSocket | null = null

    // Use a small timeout to prevent double connection in React Strict Mode
    const connectTimeout = setTimeout(() => {
      ws = new WebSocket(wsUrl)
      
      ws.onopen = () => {
        console.log("WebSocket connected for pipeline logs")
      }

      ws.onmessage = (event) => {
        const message = event.data
        
        try {
          // Try to parse as JSON
          const jsonData = JSON.parse(message)
          
          // Handle different message types based on type field
          if (jsonData.type === "steps") {
            // Handle steps list
            setSteps(jsonData.data as string[])
          } else if (jsonData.type === "step") {
            // Handle step log
            setLogs(prev => [...prev, jsonData.data as string])
            setActiveStep(jsonData.container as string)
          } else if (jsonData.type === "error") {
            // Handle error messages
            console.error("Pipeline error:", jsonData.data)
          } else {
            // Fallback for unknown types
            console.warn("Unknown message type:", jsonData.type)
          }
        } catch (e) {
          // Fallback for non-JSON messages (backward compatibility)
          if (message.startsWith("STEPS:")) {
            try {
              const stepNames = JSON.parse(message.substring(6)) as string[]
              setSteps(stepNames)
            } catch (parseError) {
              console.error("Failed to parse legacy steps format", parseError)
            }
          } else if (message.startsWith("ERROR:")) {
            console.error("Pipeline error:", message.substring(6))
          } else if (message.includes(":")) {
            // Legacy format: "stepName:logLine"
            const [stepName, ...logParts] = message.split(":")
            const logLine = logParts.join(":")
            setLogs(prev => [...prev, logLine])
            setActiveStep(stepName)
          } else {
            // Fallback for plain log messages
            setLogs(prev => [...prev, message])
          }
        }
      }

      ws.onerror = (e) => {
        console.error("WebSocket failed", e)
        if (ws) {
          ws.close()
        }
      }

      ws.onclose = () => {
        console.log("WebSocket connection closed")
      }
    }, 100)

    return () => {
      clearTimeout(connectTimeout)
      if (ws) {
        ws.close()
      }
    }
  }, [namespace, name, pipelineId])
  
  useEffect(() => {
    if (logContainerRef.current) {
      logContainerRef.current.scrollTop = logContainerRef.current.scrollHeight
    }
  }, [logs])

  return (
    <div className="flex h-full min-h-0 flex-col gap-4">
        <div className="flex items-center justify-between border-b pb-4">
          <div className="flex items-center gap-4">
            <div>
              <h2 className="text-xl font-bold">流水线详情</h2>
              <div className="flex items-center gap-2 text-sm text-muted-foreground">
                {pipeline && <>ID: <Badge variant="outline">{pipelineId}</Badge></>}
                {pipeline && <>状态: <Badge variant="outline">{pipeline.status}</Badge></>}
                {pipeline && <>创建时间: <Badge variant="outline">{dayjs(pipeline.createdTime).format('YYYY-MM-DD HH:mm:ss')}</Badge></>}
              </div>
            </div>
          </div>
        </div>

        {/* Content */}
        <div className="flex-1 flex gap-4 overflow-hidden min-h-0">
            {/* Steps Sidebar */}
            <div className="w-40 border-r pr-4 overflow-y-auto min-h-0">
                <h3 className="font-semibold mb-2">步骤</h3>
                <div className="space-y-1">
                    {steps.map(step => (
                        <div 
                            key={step} 
                            className={`p-2 rounded text-sm truncate ${activeStep === step ? 'bg-secondary font-medium' : 'text-muted-foreground'}`}
                        >
                            {step}
                        </div>
                    ))}
                    {steps.length === 0 && <div className="text-sm text-muted-foreground">Waiting for steps...</div>}
                </div>
            </div>

            {/* Logs Area */}
            <div className="flex-1 bg-black text-white rounded-md p-4 font-mono text-sm overflow-hidden flex flex-col min-h-0">
                <div ref={logContainerRef} className="flex-1 min-h-0 overflow-y-auto whitespace-pre-wrap break-all">
                    {logs.map((log, i) => (
                        <div key={i}>{log}</div>
                    ))}
                    {logs.length === 0 && <div className="text-gray-500">Waiting for logs...</div>}
                </div>
            </div>
        </div>
    </div>
  )
}
