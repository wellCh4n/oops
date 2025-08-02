'use client';

import { useEnvironmentStore } from '@/store/environment-store';
import { Watermark } from 'antd';
import type { ReactNode } from 'react'

const CommonLayout = ({ children }: { children: ReactNode }) => {

	const { currentEnvironment } = useEnvironmentStore();

	return (
		<div className="h-full">
			<Watermark className="h-full" content={`Environment: ${currentEnvironment?.name || ""}`}>
				{children}
			</Watermark>
		</div>
	)
}

export default CommonLayout
