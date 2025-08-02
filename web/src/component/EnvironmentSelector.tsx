'use client';

import { Select, Modal } from 'antd';
import { useEffect, useState } from 'react';
import { fetchEnvironmentList } from '@/service/environment';
import { useEnvironmentStore } from '@/store/environment-store';
import { EnvironmentItem } from '@/types/environment';

const EnvironmentSelector = () => {
  const { currentEnvironment, setCurrentEnvironment } = useEnvironmentStore();
  const [environments, setEnvironments] = useState<EnvironmentItem[]>([]);

  useEffect(() => {
    fetchEnvironmentList().then((envs) => {
      setEnvironments(envs);
    });
  }, []);

  return (
    <Select
      value={currentEnvironment ? { value: currentEnvironment.id, label: currentEnvironment.name } : undefined}
      onChange={(value) => {
        const selectedEnv = environments.find(env => env.id === value.value);
        if (selectedEnv && selectedEnv.id !== currentEnvironment?.id) {
          Modal.confirm({
            title: 'Switch Environment',
            content: 'Switch environment will refresh the page, are you sure to continue?',
            okText: 'Confirm',
            cancelText: 'Cancel',
            onOk: () => {
              setCurrentEnvironment(selectedEnv);
              window.location.reload();
            },
          });
        }
      }}
      labelInValue
      placeholder="Select Environment"
      style={{ width: 200 }}
      options={environments.map(env => ({
        value: env.id,
        label: env.name,
      }))}
    />
  );
};

export default EnvironmentSelector;