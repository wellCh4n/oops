'use client';

import type {ReactNode} from "react";

const NamespaceLayout = ({ children }: { children: ReactNode }) => {
  return (
    <div className="h-full">
      {children}
    </div>
  );
}

export default NamespaceLayout;