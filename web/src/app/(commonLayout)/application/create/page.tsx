'use client'

import ApplicationEditor from "@/component/ApplicationEditor";
import { useHeader } from "@/context/header-context";
import { useEffect } from "react";

export default function CreateApplicationPage() {
  const { setHeaderContent } = useHeader();

  useEffect(() => {
    setHeaderContent(
      <span>Application Create</span>
    )

    return () => {
      setHeaderContent('')
    }
  }, [setHeaderContent])

  return (
    <div className="p-3">
      <ApplicationEditor 
        mode="create" 
        application={{
          replicas: 1
        }} 
      />
    </div>
  );
}