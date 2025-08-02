'use client'

import { useHeader } from "@/context/header-context";
import { fetchApplicationPodLog } from "@/service/application";
import { useParams } from "next/navigation";
import { useEffect, useRef, useState } from "react";

export default function LogPage() {
  const params = useParams();
  const appName = params.appName as string;
  const podName = params.podName as string;
  const isFetching = useRef(false)
  const [logs, setLogs] = useState<string[]>([])
  const abortControllerRef = useRef<AbortController | null>(null);
  const logContainerRef = useRef<HTMLDivElement>(null);
  const { setHeaderContent } = useHeader();

  useEffect(() => {
    if (logContainerRef.current) {
      logContainerRef.current.scrollTop = logContainerRef.current.scrollHeight;
    }
  }, [logs]);

  useEffect(() => {
    setHeaderContent(`Application Log: ${appName} - ${podName}`)
  }, [setHeaderContent, appName, podName])

  useEffect(() => {
    if(isFetching.current) return;
    isFetching.current = true;

    abortControllerRef.current = new AbortController();

    const eventSource = fetchApplicationPodLog(appName, podName)
    
    eventSource.onopen = () => {
        console.log('EventSource opened successfully');
    };

    eventSource.addEventListener('log', (event) => {
      const messageEvent = event as MessageEvent;
      console.log('Received message:', messageEvent.data);
      setLogs(prev => [...prev, messageEvent.data]);
    });
    
    eventSource.onerror = (error) => {
        console.error('EventSource error:', error);
        isFetching.current = false;
    };

    return () => {
        setLogs([]);
        if (abortControllerRef.current) {
            abortControllerRef.current.abort();
        }
    }
    
}, [appName, podName]);

  return (
    <div className="flex h-full">
        <div 
          ref={logContainerRef}
          className="h-full flex-1 overflow-y-auto bg-black text-green-400 font-mono text-sm p-4"
        >
          {logs.length === 0 ? (
            <div className="text-gray-500 text-center py-8">
              等待日志输出...
            </div>
          ) : (
            logs.map((log, index) => (
              <div 
                key={index} 
                className="mb-1 hover:bg-gray-900 px-2 rounded transition-colors duration-200"
              >
                <span className="whitespace-pre-wrap break-all">{log}</span>
              </div>
            ))
          )}
        </div>
    </div>
  );
}