"use client";

import { useHeader } from "@/context/header-context";
import { fetchPipeline, watchPipeline } from "@/service/pipeline";
import { PipelineItem } from "@/types/pipeline";
import { Steps } from "antd";
import { useParams } from "next/navigation";
import { useEffect, useState, useRef } from "react";

export default function PipelineDetailPage() {

  const [ pipeline, setPipeline ] = useState<PipelineItem | null>(null);
  const { pipelineId, appName, namespaceName } = useParams() as { pipelineId: string, appName: string, namespaceName: string };
  const [ steps, setSteps ] = useState<string[]>([]);
  const [ stepLogs, setStepLogs ] = useState<Record<string, string[]>>({});
  const [ currentStep, setCurrentStep ] = useState<number>(0);
  const logContainerRef = useRef<HTMLDivElement>(null);
  const { setHeaderContent } = useHeader();

  const scrollToBottom = () => {
    if (logContainerRef.current) {
      logContainerRef.current.scrollTop = logContainerRef.current.scrollHeight;
    }
  };

  useEffect(() => {
    scrollToBottom();
  }, [stepLogs, currentStep]);

  useEffect(() => {
    setHeaderContent(
      <span>Pipeline {pipeline?.name}</span>
    )

    return () => {
      setHeaderContent("")
    }
  }, [pipeline, setHeaderContent])

  useEffect(() => {
    console.log(namespaceName, appName, pipelineId);
    const eventSource = watchPipeline(namespaceName, appName, pipelineId);

    eventSource.addEventListener('steps', (event) => {
      const stepNames = JSON.parse(event.data)
      setSteps(stepNames)
      
      stepNames.forEach((stepName: string) => {
        eventSource.addEventListener(stepName, (event) => {
          setStepLogs(prev => {
            const logs = prev[stepName] ? [...prev[stepName]] : [];
            logs.push(event.data);
            return { ...prev, [stepName]: logs };
          });
        })
      })
    })

    eventSource.onerror = (e) => {
      console.error("SSE error,可能会导致重连", e);
      eventSource.close();
    };

    fetchPipeline(namespaceName, appName, pipelineId).then((pipeline) => {
      setPipeline(pipeline)
    })
    
    return () => {
      setPipeline(null);
      setSteps([]);
      eventSource.close();
    }
  }, [namespaceName, appName, pipelineId])

  if (!pipeline || steps.length === 0) {
    return <></>
  }

  return (
    <div className="h-full flex flex-col p-6">
      <Steps
          className="h-10"
          current={currentStep}
          items={steps.map((step) => ({ title: step }))}
          onChange={setCurrentStep}
      />
      <div 
        ref={logContainerRef}
        className="h-full overflow-y-auto mt-6 bg-[#222] text-white p-4 rounded-lg font-mono whitespace-pre-wrap "
      >
        {(stepLogs[steps[currentStep]] || []).map((log, index) => (
          <div 
            key={index} 
            className="mb-1 hover:bg-gray-900 px-2 rounded transition-colors duration-200"
          >
            <span className="whitespace-pre-wrap break-all">{log}</span>
          </div>
        ))}
      </div>
    </div>
  )
}