'use client';

import { useEnvironmentStore } from '@/store/environment-store';
import { Skeleton, Watermark } from 'antd';
import type { ReactNode } from 'react'

const CommonLayout = ({ children }: { children: ReactNode }) => {

	const { currentEnvironment } = useEnvironmentStore();

	if (!currentEnvironment) {
		return <Skeleton active />;
	}

	return (
		<Watermark className="h-full" 
		content={`Environment: ${currentEnvironment.name}`}>
			<div className="h-full p-3 overflow-auto">
				{children}
			</div>
		</Watermark>
	)
}

export default CommonLayout
