'use client';

import type { ReactNode } from 'react'

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
