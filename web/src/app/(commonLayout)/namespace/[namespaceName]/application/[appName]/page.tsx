'use client'

import { useApplicationContext } from "@/context/application-context";
import ApplicationEditor from "@/component/ApplicationEditor";
import { useHeader } from "@/context/header-context";
import { useEffect } from "react";

export default () => {
  const application = useApplicationContext();
  const { setHeaderContent } = useHeader();

  useEffect(() => {
    setHeaderContent(
      <>
        <span>Application {application?.name} Edit</span>
      </>
    )
  }, [application])

  if (!application) {
    return <></>
  }

  return (
    <div>
      <ApplicationEditor mode="edit" application={application} />
    </div>
  );
}
