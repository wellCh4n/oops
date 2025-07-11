'use client';

import type { ReactNode } from 'react'
import { theme } from 'antd';

const CommonLayout = ({ children }: { children: ReactNode }) => {
	return (
		<div
			className="h-full"
		>
			{children}
		</div>
	)
}

export default CommonLayout
