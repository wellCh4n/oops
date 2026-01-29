"use client"

import { use, useState, useEffect, useRef } from "react"
import { useRouter } from "next/navigation"
import { getPipeline } from "@/lib/api/pipelines"
import { Pipeline } from "@/lib/api/types"
import { API_BASE_URL } from "@/lib/api/config"
import { Button } from "@/components/ui/button"
import { ArrowLeft } from "lucide-react"
import { Badge } from "@/components/ui/badge"
import { toast } from "sonner"

interface PageProps {
  params: Promise<{
    namespace: string
    name: string
    pipelineId: string
  }>
}

export default function PipelineDetailPage({ params }: PageProps) {
  const router = useRouter()
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
    const url = `${API_BASE_URL}/api/namespaces/${namespace}/applications/${name}/pipelines/${pipelineId}/watch`
    const eventSource = new EventSource(url)
    
    eventSource.addEventListener("steps", (event) => {
      try {
        const stepNames = JSON.parse(event.data) as string[]
        setSteps(stepNames)
        
        stepNames.forEach(stepName => {
            eventSource.addEventListener(stepName, (e) => {
                setLogs(prev => [...prev, e.data])
                setActiveStep(stepName)
            })
        })
      } catch (e) {
        console.error("Failed to parse steps", e)
      }
    })

    eventSource.onerror = (e) => {
        // console.error("EventSource failed", e)
        // eventSource.close()
    }

    return () => {
      eventSource.close()
    }
  }, [namespace, name, pipelineId])
  
  useEffect(() => {
    if (logContainerRef.current) {
      logContainerRef.current.scrollTop = logContainerRef.current.scrollHeight
    }
  }, [logs])

  return (
    <div className="flex flex-col h-[calc(100vh-2rem)] p-6 space-y-4">
        {/* Header */}
        <div className="flex items-center justify-between border-b pb-4">
             <div className="flex items-center gap-4">
                <Button variant="ghost" size="icon" onClick={() => router.back()}>
                    <ArrowLeft className="h-4 w-4" />
                </Button>
                <div>
                    <h2 className="text-xl font-bold">流水线详情</h2>
                    <div className="flex items-center gap-2 text-sm text-muted-foreground">
                        <span>ID: {pipelineId}</span>
                        {pipeline && <Badge variant="outline">{pipeline.status}</Badge>}
                    </div>
                </div>
             </div>
        </div>

        {/* Content */}
        <div className="flex-1 flex gap-4 overflow-hidden">
            {/* Steps Sidebar */}
            <div className="w-64 border-r pr-4 overflow-y-auto">
                <h3 className="font-semibold mb-2">步骤</h3>
                <div className="space-y-1">
                    {steps.map(step => (
                        <div 
                            key={step} 
                            className={`p-2 rounded text-sm ${activeStep === step ? 'bg-secondary font-medium' : 'text-muted-foreground'}`}
                        >
                            {step}
                        </div>
                    ))}
                    {steps.length === 0 && <div className="text-sm text-muted-foreground">Waiting for steps...</div>}
                </div>
            </div>

            {/* Logs Area */}
            <div className="flex-1 bg-black text-white rounded-md p-4 font-mono text-sm overflow-hidden flex flex-col">
                <div ref={logContainerRef} className="h-full overflow-y-auto whitespace-pre-wrap break-all">
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
